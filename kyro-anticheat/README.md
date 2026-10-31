# KYRO AntiCheat 3.8.0

Anti-Cheat für **Paper und Folia 1.21.11** (Java 21): tick-genaue Bewegungs-Prediction, Kampf-, Block-
und Inventar-Checks, Setbacks auf validierten Boden, persistente Flag-Historie (SQLite) und die
`/sus`-GUI für Staff. Keine automatischen Kicks oder Bans.

## Installation

1. Server stoppen und Pluginordner einschließlich `flags.db` sichern.
2. Alte KYRO-JAR entfernen und `KyroAntiCheat-3.8.0.jar` einsetzen. Nie zwei Versionen gleichzeitig.
3. Server vollständig neu starten, kein `/reload`.

Die bestehende `config.yml` und die Datenbank werden weiterverwendet; das Schema bleibt unverändert.
Fehlende neue Schlüssel nutzen die Standardwerte, die Datei wird nicht überschrieben.

## Bauen und testen

```bash
mvn package                                   # Plugin-JAR: target/KyroAntiCheat-3.8.0.jar
mvn test                                      # 215 Tests, ohne Server
mvn test -Dtest=PredictionFuzzTest -Dfuzz.seeds=90000   # großer Fehlalarm-Fuzz (ca. 10 min)
```

Die Paper-API kommt aus `https://repo.papermc.io/repository/maven-public/`.

## Projektstruktur

| Paket | Inhalt |
|---|---|
| `KyroAntiCheat` | Plugin-Einstieg: Config laden, Checks bauen, Listener/Commands registrieren, Reload |
| `checks.movement` | Fly A/B/C, Speed A/B, Step, NoSlow A/B, Sprint A, Timer, NoFall, Jesus, ElytraFly |
| `checks.combat` | Reach, KillAura A–D, Criticals, Velocity, AutoClicker |
| `checks.action` | Scaffold A/B, FastPlace, Nuker, GhostHand, XRay, InventoryMove |
| `checks.vehicle` | BoatFly |
| `listener` | Bukkit-Events → Checks (Bewegung, Kampf, Mining, Knockback, Hover-Heartbeat) |
| `core` | Spielerzustand (`PlayerData`), Physik, Kollisionen, Konfiguration, Setbacks, Check-Registry |
| `flag` | Flag-Pipeline: Drosselung, Violation-Level, Confidence, Alerts |
| `storage` | Asynchrone SQLite-Ablage der Flags inkl. Migrationen |
| `gui`, `command` | `/sus`-GUI, Inspektion, `/kyro`- und `/acalerts`-Befehle |

Jeder Check ist in `core/CheckRegistry` mit Messmethode und bekannten Grenzen beschrieben
(`/kyro checks` im Spiel). Konfiguration: `src/main/resources/config.yml`.

## Standardverhalten: vorsichtig

- `checks.timer.prevent: false` – Timer meldet nur, blockiert nichts.
- `prevention.protect-setbacks: false` – Teleport-Abbrüche anderer Plugins werden respektiert.
- Der Hover-Heartbeat korrigiert die Position, erzeugt aber kein Flag und keinen Verdachtswert.

## Strenger Modus (optional)

```yaml
prevention:
  protect-setbacks: true    # KYRO-Setbacks überstimmen fremde Teleport-Abbrüche (z. B. Combat-Tag)
checks:
  timer:
    prevent: true           # überzählige Bewegungen ablehnen (kein Boden-Setback, Schuld bleibt bestehen)
```

## Neu in 3.8.0

### Geschlossene Bypässe

- **Timer A – absichtliche Pausen**: Nach jeder Lücke über 300 ms gab es bisher 1,5 s *ohne jede
  Grenze*. Ein Timer-Cheat konnte das mit kurzen Pausen auslösen und dazwischen doppelt so schnell
  laufen. Jetzt bringt eine Lücke nur so viel Guthaben, wie wirklich Zeit verloren ging. Das sind
  höchstens 1 s, bei echtem Server-Lag bis 10 s. Server-Lag wird über einen Heartbeat pro Spieler
  erkannt. Das Guthaben verfällt. Nachgeholte Pakete nach echtem Netzwerk-Lag bleiben erlaubt.
- **Timer A im PvP**: Der Timer war bei Knockback, Elytra und Teleport-Grace ausgesetzt. Jeder Treffer
  schaltete ihn 2,5 s oder länger ab. Die Paketrate hängt nicht von der Physik ab, deshalb läuft er
  jetzt immer, außer in Fahrzeugen.
- **NoSlow B (neu)**: Der Schleich-Status wurde erfasst, aber nie geprüft. Volles Tempo beim
  Schleichen war unerkannt. Jetzt nutzt NoSlow B dieselbe Momentum-Prediction wie Speed B, mit dem
  `sneaking_speed`-Attribut (Swift Sneak wird berücksichtigt).
- **Sprint A (neu)**: Speed B erlaubte immer Sprinttempo, auch bei Hunger ≤ 6, wo Vanilla nicht sprinten
  kann. Hunger- bzw. Omni-Sprint brachte so 30 % mehr Tempo. Ein Sprint-Start bei Hunger wird abgelehnt
  und geflaggt. Hungrige Spieler, die der Server nicht sprinten sieht, bekommen kein Sprinttempo und
  keinen Sprint-Sprung-Boost mehr.
- **KillAura D (neu)**: Angriffe ohne den Arm-Schwung, den jeder Vanilla-Client direkt nach dem Angriff
  sendet („NoSwing"-Module vieler KillAuras). Die Prüfung läuft 5 Ticks später auf dem Scheduler des
  Spielers (Folia-sicher). Bei hohem Buffer werden weitere Treffer abgebrochen.

### Fehlalarm-Korrekturen (vom erweiterten Fuzz-Test gefunden)

- **Attribut-Hold**: Nach einer Senkung des Bewegungstempo-Attributs (Speed endet, Sprint endet) wird
  600 ms + Ping lang das alte Tempo erlaubt, weil der Client den neuen Wert erst nach seinem Ping
  anwendet. Bei zwei Senkungen kurz hintereinander bekam die zweite bisher keine eigene Wartezeit.
  Der pauschale Sprint-Puffer hat das bisher verdeckt.
- **Schwung bei tolerierten NoSlow-Moves**: Ein erster, tolerierter Move über der NoSlow-Grenze (z. B.
  Landung mit Knockback-Schwung beim Schleichen) wurde mit der Schleich-Grenze statt mit dem
  beobachteten Tempo fortgeschrieben. Der folgende Move wurde dadurch zu streng beurteilt.

### Code und Tests

- Einheitlich formatiert (google-java-format, AOSP). Dekompilier-Artefakte (`var24`, ausgeschriebene
  try-with-resources, tote Zuweisungen) sind entfernt, ohne Verhaltensänderung: Alle 193 bestehenden
  Tests laufen unverändert.
- 22 neue Tests spielen die alten Bypass-Muster nach und prüfen Vanilla-Verhalten.
- Der Fuzz-Test simuliert jetzt auch, dass der Server Schleichen und Hunger sieht. Die Hunger-Werte
  erreichen den Client mit Ping-Verzögerung.

Neue Config-Schlüssel:

```yaml
checks:
  sprint:
    enabled: true
    buffer: {flag-at: 2.0}
  killaura:
    noswing:
      enabled: true          # deaktivieren, falls ein Plugin Spieler per API (Player#attack) ohne Schwung angreifen lässt
      buffer: {flag-at: 3.0}
```

## Grenzen

Kein Anti-Cheat ist „bypass-sicher". KYRO erkennt ereignisbasiert über Paper-Events und hat keine
eigene Paket-Ebene und keine vollständige Client-Simulation. Was der Server nicht sieht, kann KYRO
nicht prüfen, z. B. Pakete ohne Positionsänderung oder Rotationen zwischen zwei Moves. Bewusste
Toleranzen gegen Fehlalarme lassen einen kleinen Spielraum, etwa 0,03–0,04 Blöcke pro Tick in der
Prediction oder +0,3 Blöcke Reach. Es gibt keine zuverlässige Teil-Knockback-Erkennung und keine
AutoClicker-Erkennung unterhalb der CPS-Grenze. Bedrock bleibt standardmäßig ausgenommen. XRay bleibt
eine Verdachtsstatistik.

Kein Live-Test auf einem echten Server mit echten Clients: vor dem Produktiveinsatz auf einem
Testserver mit den eigenen Plugins prüfen.
