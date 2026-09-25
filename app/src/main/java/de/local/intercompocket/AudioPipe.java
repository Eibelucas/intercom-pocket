package de.local.intercompocket;

import android.content.Context;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Process;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class AudioPipe {
    interface Sink{void frame(byte[] bytes);void failed(String message);}
    private final AudioManager manager;
    private final Context context;
    private final Sink sink;
    private final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(6);
    private volatile boolean running,sending;
    volatile double level;
    private AudioRecord record;
    private AudioTrack track;
    private AcousticEchoCanceler echo;
    private Thread capture,playback;
    private AudioFocusRequest focus;
    private boolean managed;
    synchronized void telecomManaged(boolean on){if(running)throw new IllegalStateException("Audio already running");managed=on;}
    volatile boolean microphoneAvailable;
    volatile String microphoneProblem="",startupStep="";
    private CallDiagnostics diagnostics=new CallDiagnostics();
    void diagnostics(CallDiagnostics d){diagnostics=d;}
    AudioPipe(Context c,Sink sink){context=c;manager=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);this.sink=sink;}
    synchronized void start(boolean microphone) throws Exception {
        if(running)return;
        microphoneAvailable=false;microphoneProblem="";
        startupStep="Audiofokus";
        AudioAttributes attr=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        if(!managed){
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attr).setOnAudioFocusChangeListener(change->{if(running&&(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))sink.failed("Durch einen anderen Anruf unterbrochen");},new android.os.Handler(android.os.Looper.getMainLooper())).build();
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new Exception("Audio ist gerade belegt");
        startupStep="Audiomodus";
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        try{speaker(true);}catch(RuntimeException ex){diagnostics.error("Lautsprecherauswahl (optional)",ex);}
        }
        startupStep="Audiowiedergabe";
        AudioFormat format=new AudioFormat.Builder().setSampleRate(Wire.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        track=new AudioTrack.Builder().setAudioAttributes(attr).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(7680,AudioTrack.getMinBufferSize(Wire.RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT))).build();
        if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new Exception("Lautsprecher nicht verfügbar");
        if(microphone){
            startupStep="Mikrofon";
            openMicrophone();
        }
        startupStep="Wiedergabe starten";track.play();running=true;
        playback=new Thread(()->{
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            try{while(running){byte[] b=queue.poll(200,TimeUnit.MILLISECONDS);if(b!=null){track.write(b,0,b.length,AudioTrack.WRITE_BLOCKING);if(!sending)level=meter(b);}}}catch(Exception e){if(running)sink.failed("Audiowiedergabe unterbrochen");}
        },"intercom-playback");playback.start();
        if(record!=null){capture=new Thread(()->{
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);byte[] frame=new byte[Wire.FRAME_BYTES];int filled=0;
            try{while(running){int n=record.read(frame,filled,frame.length-filled,AudioRecord.READ_BLOCKING);if(n<0)throw new IllegalStateException();filled+=n;if(filled==frame.length){if(sending){level=meter(frame);sink.frame(Arrays.copyOf(frame,frame.length));}filled=0;}}}catch(Exception e){if(running){diagnostics.error("Mikrofon lesen",e);sink.failed("Mikrofon unterbrochen");}}
        },"intercom-microphone");capture.start();}
        startupStep="bereit";diagnostics.event(microphoneAvailable?"Audio bereit, Mikrofon aktivierbar":"Audio bereit, nur Hören");
    }
    private void openMicrophone(){
        if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){microphoneProblem="Mikrofonberechtigung fehlt";diagnostics.event(microphoneProblem);return;}
        int[] sources={MediaRecorder.AudioSource.VOICE_COMMUNICATION,MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.MIC};
        for(int source:sources){
            try{
                int minimum=AudioRecord.getMinBufferSize(Wire.RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(minimum<=0)throw new IllegalStateException("unsupported capture format");
                record=new AudioRecord(source,Wire.RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(10240,minimum));
                if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("capture not initialized");
                // Some devices advertise an effect they cannot instantiate. It must not end a call.
                try{if(AcousticEchoCanceler.isAvailable()){echo=AcousticEchoCanceler.create(record.getAudioSessionId());if(echo!=null)echo.setEnabled(true);}}
                catch(RuntimeException effectError){diagnostics.error("Echounterdrückung (optional)",effectError);if(echo!=null)try{echo.release();}catch(RuntimeException ignored){}echo=null;}
                record.startRecording();
                if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("capture did not start");
                microphoneAvailable=true;diagnostics.event("Mikrofonquelle "+source+" gestartet");return;
            }catch(RuntimeException ex){
                diagnostics.error("Mikrofonquelle "+source,ex);
                if(echo!=null)try{echo.release();}catch(RuntimeException ignored){}echo=null;
                if(record!=null)try{record.release();}catch(RuntimeException ignored){}record=null;
            }
        }
        microphoneProblem="Android konnte keine Mikrofonquelle starten";
    }
    void sending(boolean on){sending=on&&microphoneAvailable;}
    void receive(byte[] b){if(!running||b.length==0||b.length>8192||(b.length&1)!=0)return;if(!queue.offer(b)){queue.poll();queue.offer(b);}}
    void speaker(boolean on){if(managed)return;for(AudioDeviceInfo d:manager.getAvailableCommunicationDevices())if(d.getType()==(on?AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)){manager.setCommunicationDevice(d);break;}}
    synchronized void stop(){
        running=false;sending=false;microphoneAvailable=false;level=0;
        if(record!=null)try{record.stop();}catch(Exception ignored){}
        if(track!=null)try{track.pause();track.flush();}catch(Exception ignored){}
        join(capture);join(playback);
        if(record!=null){record.release();record=null;}if(track!=null){track.release();track=null;}if(echo!=null){echo.release();echo=null;}
        queue.clear();if(!managed){manager.clearCommunicationDevice();manager.setMode(AudioManager.MODE_NORMAL);if(focus!=null)manager.abandonAudioFocusRequest(focus);}focus=null;managed=false;
    }
    private void join(Thread t){if(t!=null&&t!=Thread.currentThread())try{t.join(600);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    static double meter(byte[] b){double sum=0;for(int i=0;i+1<b.length;i+=2){int v=(short)((b[i]&255)|(b[i+1]<<8));sum+=(double)v*v;}return Math.min(1,Math.sqrt(sum/Math.max(1,b.length/2))/8000);}
}
