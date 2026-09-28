# Intercom Satelite 1.3.0 – Prüfbericht

Stand: 28.09.2026. Vorbereitung einer neuen Version; keine signierte Veröffentlichung.

## Neue Funktionen

- Lokale Raumgruppen mit stabilen Kiosk-IDs, Bearbeiten und Löschen.
- Durchsagen an alle verfügbaren Kiosks, einzelne ausgewählte Kiosks oder eine Gruppe; Empfängerstatus je Raum.
- Separater Schalter für eingehende Durchsagen. Einzelanrufe bleiben möglich, sofern die bestehenden Ruhe-Regeln sie zulassen.
- Zeitlich begrenztes „Nicht stören“ für 30 Minuten, zwei Stunden oder bis morgen um 07:00 Uhr. Der Ablaufzeitpunkt bleibt nach einem Neustart erhalten; geplante Ruhezeiten bleiben wirksam.

## Ausgeführte Prüfungen

Mit Gradle 8.13, Android SDK/Build-Tools 35 und OpenJDK 21 (Java-Zielversion 17):

```
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

- 48 JVM-Tests bestanden, 0 Fehler. Davon 17 zusätzliche Tests für Gruppen, Empfängerauswahl, Durchsage-Empfang, Ablaufzeiten und Fehlerisolation.
- Die Durchsage-Tests verwenden echte lokale HTTP-/WebSocket-Verbindungen und ersetzen nur Android-Audiohardware.
- Geprüft: kein Ausweichen auf alle Kiosks bei leerer oder nicht erreichbarer Auswahl; nur ein Anruf je Kiosk-ID auch bei mehreren Adressen; weiterlaufende Durchsage bei Ablehnung oder fehlgeschlagenem Audioaufbau eines Empfängers.
- Geprüft: Gruppen nach erneutem Laden, ungültige Änderungen ohne Datenverlust, Ablauf exakt am gespeicherten Zeitpunkt, Abbrechen des Timers sowie Ruhezeiten und Ausnahmen nach Ablauf.
- Android Lint: 0 Fehler, 20 Warnungen (Akku-/Wake-Lock-Handhabung, eigene Zertifikatsprüfung, Bedienbarkeit und bestehende direkt gesetzte Oberflächentexte).
- Release-Build erfolgreich; Ausgabe ist unsigniert.
- `git diff --check` ohne Befund.

## Noch auf Geräten zu prüfen

Zusätzlich wurde die Oberfläche in einem separaten Android-15-Telefon-Emulator (Pixel-6-Profil) bedient. Zwei lokale simulierte Kiosks standen bereit. Erfolgreich geprüft:

- Einrichtung und manuelles Hinzufügen beider Testkiosks.
- Gruppe anlegen, bearbeiten und löschen; Mitgliedschaft und Timer bleiben nach Beenden und Neustarten des App-Prozesses erhalten.
- Durchsage an die Gruppe mit einem ausgewählten Kiosk. Der simulierte zweite Kiosk erhielt keine Einladung; die Ansicht zeigte den tatsächlich verbundenen Empfänger.
- „Nicht stören“ für 30 Minuten einstellen und manuell ausschalten.
- Durchsage-Empfang in den Einstellungen ausschalten. Authentifizierte Testanfragen wurden anschließend für `broadcast` mit `dnd`, für Einzelanrufe mit `ringing` beantwortet.
- Gruppenansicht bei 720 × 1280 Pixeln, 320 dpi und Schriftfaktor 1,3; die Bedienelemente waren erreichbar und die Gruppe ließ sich löschen.

Vor einer regulären Veröffentlichung bleiben Tests auf physischen Geräten mit echten Kiosks erforderlich: Mikrofon- und Lautsprecherqualität, Samsung-Telefon-App, Sperrbildschirmempfang und Benachrichtigung nach Ablauf von „Nicht stören“. OEM-Energiesparverhalten wird durch den Emulator nicht nachgebildet.

Paket-ID unverändert; Versionscode 6, Versionsname 1.3.0. Ein Update der vorhandenen Installation benötigt den ursprünglichen privaten Signaturschlüssel, der nicht im Repository enthalten ist.

## Separate lokale Test-APK

Für einen Test ohne den bislang nicht verfügbaren ursprünglichen Signaturschlüssel wurde außerhalb dieses Repositorys eine separate APK gebaut: Paket-ID `de.local.intercompocket.preview`, Versionsname `1.3.0-preview`, Beschriftung „Intercom Satelite Test“. Die Anwendungslogik entspricht dem Feature-Commit `fb223a2201c09a1ca538d665bd26ea2e67f80702`; nur Paket-ID, Versionsname und Manifest-Beschriftung wurden angepasst.

Die Test-APK wurde mit einem neuen privaten Schlüssel signiert, die Signatur geprüft und die Installation samt Start neben dem ursprünglichen Paket im Emulator bestätigt. Der Schlüssel wurde separat lokal gesichert und nicht im Repository abgelegt. Die Test-App hat eigene Einstellungen. Vor Aktivierung ihres Empfangs muss der Empfang der bisherigen App ausgeschaltet werden, da beide dieselben Ports verwenden. Diese APK ist kein Update für die bisherige Installation und wurde nicht als reguläres GitHub-Release veröffentlicht.
