package de.local.intercompocket;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

final class ThemePalette {
    final int primary,onPrimary,background,surface,surfaceAlt,onSurface,muted,outline,error;
    private ThemePalette(int primary,int onPrimary,int background,int surface,int surfaceAlt,int onSurface,int muted,int outline,int error){
        this.primary=primary;this.onPrimary=onPrimary;this.background=background;this.surface=surface;this.surfaceAlt=surfaceAlt;this.onSurface=onSurface;this.muted=muted;this.outline=outline;this.error=error;
    }
    static ThemePalette load(Context context,Config config){
        boolean dark=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        String choice=config.prefs.getString("accent","teal");
        int primary=switch(choice){case "blue"->dark?0xffa5c8ff:0xff315b91;case "plum"->dark?0xffdbb6e4:0xff76527f;case "dynamic"->dynamic(context,dark);default->dark?0xff8dcbd0:0xff386e72;};
        return new ThemePalette(primary,dark?0xff153033:Color.WHITE,dark?0xff11191a:0xfffaf8f5,dark?0xff1b2527:Color.WHITE,dark?0xff273638:0xffeaf1ef,dark?0xffe4efee:0xff1b2526,dark?0xffacc0c0:0xff526161,dark?0xff526365:0xffc4d2d0,dark?0xffffb4ab:0xffa33c32);
    }
    private static int dynamic(Context context,boolean dark){
        int id=dark?android.R.color.system_accent1_200:android.R.color.system_accent1_600;
        try{return context.getColor(id);}catch(Exception ignored){}
        return dark?0xff8dcbd0:0xff386e72;
    }
}
