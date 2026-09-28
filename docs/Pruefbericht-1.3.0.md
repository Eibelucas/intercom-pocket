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

Die neue Oberfläche wurde nicht in einem Telefon-Emulator oder auf einem physischen Gerät bedient. Vor einer Veröffentlichung: Gruppen anlegen und bearbeiten, kleine Bildschirme/große Schrift, Durchsagen mit mehreren echten Kiosks, Sperrbildschirmempfang und Benachrichtigung nach Ablauf von „Nicht stören“ prüfen. Mikrofon, Samsung-Telefon-App und OEM-Energiesparverhalten sind durch die JVM-Tests nicht abgedeckt.

Paket-ID unverändert; Versionscode 6, Versionsname 1.3.0. Ein Update der vorhandenen Installation benötigt den ursprünglichen privaten Signaturschlüssel, der nicht im Repository enthalten ist.
