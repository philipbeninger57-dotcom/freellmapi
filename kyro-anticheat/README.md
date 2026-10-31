# KYRO AntiCheat 3.7.2

Zusammenführung von 3.7.0 (tick-genaue Vorhersage-Schicht) und 3.7.1-custom (Fehlalarm-Korrekturen,
Alert-Bündelung, Diagnose, Retention). Kein offizielles Upstream-Release.
Zielplattform: Java 21, Paper/Folia 1.21.11.

## Installation

1. Server stoppen und Pluginordner einschließlich `flags.db` sichern.
2. Alte KYRO-JAR entfernen und `KyroAntiCheat-3.7.2.jar` einsetzen. Nie zwei Versionen gleichzeitig.
3. Server vollständig neu starten, kein `/reload`.

Die bestehende `config.yml` und die Datenbank werden weiterverwendet; das Schema bleibt unverändert.
Fehlende neue Schlüssel nutzen die Standardwerte, die Datei wird nicht überschrieben.

## Standardverhalten: vorsichtig

Die Standardwerte sind die vorsichtigen aus 3.7.1-custom:

- `checks.timer.prevent: false` – Timer meldet nur, blockiert nichts.
- `prevention.protect-setbacks: false` – Teleport-Abbrüche anderer Plugins werden respektiert.
- Der Hover-Heartbeat korrigiert die Position, erzeugt aber kein Flag und keinen Verdachtswert.

## Strenger Modus (optional)

Beide Schalter einzeln aktivierbar, Server danach neu starten oder `/kyro reload`:

```yaml
prevention:
  protect-setbacks: true    # KYRO-Setbacks überstimmen fremde Teleport-Abbrüche (z. B. Combat-Tag)
checks:
  timer:
    prevent: true           # überzählige Bewegungen ablehnen (kein Boden-Setback, Schuld bleibt bestehen)
```

`protect-setbacks: true` ist ein bewusstes Übersteuern fremder Teleportregeln; der Start protokolliert
eine entsprechende Warnung. `timer.prevent: true` weist nur die überzähligen Bewegungen zurück und
fordert nie eine Bodenrücksetzung an.

## Was gegenüber 3.7.1-custom neu ist

- **Timer-Blockade als Schalter** statt fest aus. Anders als in 3.7.0 ohne Boden-Setback.
- **InventoryMove**: `checks.inventorymove.open-grace-ms` (Standard 25 ms statt fest 250 ms).
  Dadurch werden schnelle Inventar-Cheats (AutoArmor, Inventory Manager) wieder ab dem dritten Klick
  verhindert, ohne den Fehlalarm beim normalen Doppelklick direkt nach dem Öffnen zurückzuholen.
  Die Regel „eine Bewegungsstichprobe zählt nur einmal“ aus 3.7.1-custom bleibt erhalten und ist
  der eigentliche Grund, warum der Fehlalarm nicht wiederkommt.
- Registry- und Konfigurationstexte beschreiben die beiden Schalter.

Alles andere ist unverändert aus 3.7.1-custom übernommen: gebündelte Staff-Alerts, TPS-abhängige
Confidence, ratenbegrenzte Combat-/GhostHand-Diagnose mit Stacktrace, optionale Retention
abgeschlossener Flags, korrekturfreier Hover-Heartbeat.

## Weiter bestehende Grenzen

Ereignisbasierte Erkennung, keine vollständige Paket-/Client-Simulation. Keine automatischen Kicks
oder Bans. Keine zuverlässige Teil-Knockback-Erkennung, keine AutoClicker-Erkennung unterhalb der
CPS-Grenze. Bedrock bleibt standardmäßig ausgenommen. XRay bleibt eine Verdachtsstatistik.
Kein Live-Test auf einem echten Server mit echten Clients; vor dem Produktiveinsatz auf einem
Testserver mit den eigenen Plugins prüfen.

## Bauen

Mit JDK 21 und Maven: `mvn clean verify`. Benötigt Zugriff auf `repo.papermc.io`.
