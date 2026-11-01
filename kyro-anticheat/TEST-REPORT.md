# Testbericht — KYRO AntiCheat 3.8.0

Prüfdatum: 01.11.2026. Grundlage: Quellcode 3.7.2 (Bytecode-identisch mit dem 3.7.2-Release-JAR).

**Ausgeführt: 215 Tests, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen. `mvn clean package`: BUILD SUCCESS.**
Davon 193 bestehende Tests unverändert übernommen und 22 neue Tests.

Umgebung: Linux, OpenJDK 21.0.12.1, Maven 3.9.11, Paper-API 1.21.11 (aus dem offiziellen PaperMC-Quellcode
kompiliert, Branch `ver/1.21.11`, weil `repo.papermc.io` aus der Build-Umgebung gesperrt war).

## Fehlalarm-Test gegen Vanilla-Physik

`PredictionFuzzTest` mit `-Dfuzz.seeds=90000`: **90.000 Spielverläufe mit je 2.000 Ticks, zusammen
180 Millionen Ticks legitimer Bewegung, 0 Ablehnungen** (650 s).

Neu gegenüber 3.7.2: Die Simulation meldet dem Server jetzt auch Schleichen (`sneaking_speed` 0,3) und
den Hunger. Der Hunger erreicht den Client mit Ping-Verzögerung, und ein Sprint-Start ist nur möglich,
wenn der Client mehr als 6 Hunger sieht. Damit werden NoSlow B und die hungerabhängige
Sprint-Prediction mit geprüft.

Kontrolle: Mit absichtlich falsch gemeldetem Schleichfaktor (0,1 statt 0,3) lehnt NoSlow B legitime
Verläufe ab. Die neue Regel wird also tatsächlich ausgeübt.

Der erweiterte Fuzz-Test hat drei Probleme gefunden, die vor dem Release behoben wurden:

| Fund | Ursache | Behebung |
|---|---|---|
| Speed B nach zwei Tempo-Senkungen kurz hintereinander (5 Verläufe) | Der Attribut-Hold startete bei der zweiten Senkung nicht neu. Der pauschale Sprint-Puffer hatte das bisher verdeckt. | Jede Senkung bekommt eigene 600 ms + Ping |
| NoSlow B nach Landung mit Knockback beim Schleichen (1 Verlauf) | Ein tolerierter Move wurde mit der Schleich-Grenze statt mit dem beobachteten Tempo fortgeschrieben | Beobachtetes Tempo übernehmen, begrenzt durch die normale Grenze |
| Speed B bei Sprung im selben Tick wie ein Kantenabsturz (1 Verlauf) | Harness-Fehler: „kam vom Boden" wurde mit der bereits abgesenkten Bodenhöhe berechnet | Harness nutzt die Bodenhöhe am Start des Moves, wie `WorldGeometry` auf dem Server |

## Neue Tests (Auswahl)

| Szenario | Ergebnis |
|---|---|
| Timer: 5× (320 ms Pause + 40 Moves mit doppeltem Tempo), in 3.7.2 nie erkannt | Timer A geflaggt |
| Timer: 1 s Netzwerk-Lag, danach 19 nachgeholte Moves | kein Flag |
| Timer: 3 s Server-Stillstand, danach 59 nachgeholte Moves | kein Flag |
| Timer: 3 s Client-Pause bei laufendem Server, danach 60 Moves auf einmal | Timer A geflaggt |
| Schleichen mit Gehtempo (0,215 Blöcke/Tick) | NoSlow B geflaggt, Moves abgelehnt |
| Schleichen mit Schleichtempo; Swift Sneak III mit Gehtempo | kein Flag |
| Sprinttempo bei Hunger 4, Server sieht kein Sprinten (in 3.7.2 erlaubt) | Speed B, Moves abgelehnt |
| Sprinten bei Hunger 4, Sprint vor dem Hungerabfall begonnen | kein Flag |
| Sprint-Start bei Hunger 6 | abgelehnt, Sprint A |
| Sprint-Start innerhalb von Ping + 300 ms nach Hungerabfall | nicht beurteilt |
| 3 Angriffe ohne Arm-Schwung | KillAura D |
| 50 Angriffe mit Schwung; jeder 10. ohne Schwung | kein Flag |

Kein Live-Test auf einem echten Server mit echten Clients; vor dem Produktiveinsatz auf einem
Testserver mit den eigenen Plugins prüfen.

---

# Testbericht — KYRO AntiCheat 3.7.2

Prüfdatum: 31.10.2026. Zusammengeführter Build aus 3.7.0 und 3.7.1-custom.

**Ausgeführt: 193 Tests, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen. `mvn clean verify`: BUILD SUCCESS.**

Umgebung: Linux, OpenJDK 21.0.12.1, Maven 3.9.11, Paper-API 1.21.11 (aus dem offiziellen PaperMC-Quellcode
kompiliert, Branch `ver/1.21.11`, Commit `c5eb079`, weil `repo.papermc.io` aus der Build-Umgebung gesperrt war).
Klassenformat Java 21. Alle 189 Tests aus 3.7.1-custom laufen unverändert, dazu 4 neue Tests für die
3.7.2-Schalter.

## Fehlalarm-Test gegen Vanilla-Physik

Der Zufallstest (`PredictionFuzzTest`) mit der Vanilla-Physik-Nachbildung wurde für diesen Build erneut
ausgeführt: **30.000 Spielverläufe mit je 2.000 Ticks, zusammen 60 Millionen Ticks legitimer Bewegung,
0 Ablehnungen.** Enthalten sind Springen, Sprint-Jumping, Schleichen, Kopfstoß-Decken, Eis/Blaueis,
Speed-/Jump-Boost-Wechsel mit Ping-Verzögerung, Abgründe sowie Rückstoß und Explosionen mit zufälliger
Ping-Verzögerung von 20–300 ms. (Für 3.7.0 waren es 90.000 Verläufe; die Vorhersage-Schicht ist in 3.7.2
unverändert.)

## Direkter Vergleich der drei Builds

Gleiche Szenarien, jeweils gegen alle drei Builds ausgeführt:

| Szenario | 3.7.0 | 3.7.1-custom | 3.7.2 |
|---|---|---|---|
| Timer-Cheat (40 Bewegungen in 0,4 s), Standard | 31 von 40 abgelehnt, 31 Boden-Setbacks | 0, nur Meldung | 0, nur Meldung |
| Timer-Cheat mit `timer.prevent: true` | Schalter nicht vorhanden | Schalter nicht vorhanden | 31 von 40 abgelehnt, **0 Boden-Setbacks** |
| AutoArmor-Burst (4 Klicks in 150 ms beim Sprinten) | 3 abgebrochen | 0 abgebrochen | 2 abgebrochen (ab dem 3. Klick) |
| Weiterlaufen mit offenem Inventar (10 Klicks) | 10 abgebrochen | 7 abgebrochen | 10 abgebrochen |
| Normaler Doppelklick direkt nach dem Öffnen | 1 abgebrochen (Fehlalarm) | 0 | **0** |

## Neue Tests in 3.7.2

- Standard-Grace ist 25 ms und über `checks.inventorymove.open-grace-ms` einstellbar (0–1000 ms).
- Schneller Inventar-Cheat beim Sprinten wird ab dem dritten Klick verweigert und bleibt danach verweigert.
- Normaler Doppel-/Dreifachklick auf dieselbe Bewegungsstichprobe wird nie verweigert und erzeugt kein Flag.
- Beim Öffnen einer Kiste während des Laufens zählen die Stichproben von vor dem Öffnen nicht.

## Nicht geprüft

Kein laufender Paper-/Folia-Server, kein echter Minecraft-Client, keine gemessene TPS-Last, keine
belegte Erkennungs- oder Fehlalarmquote im Live-Betrieb. Vor dem Produktiveinsatz auf einem Testserver
mit den eigenen Plugins prüfen, besonders eigene Knockback-/Dash-/Launchpad-Plugins, Kolben-Maschinen,
Geyser/Floodgate und Spieler mit hohem Ping.

---

# Testbericht — KYRO AntiCheat 3.7.1-custom

Prüfdatum: 31.10.2026. Individueller Fix-Build aus den bereitgestellten Quellen.

**Aktuell tatsächlich ausgeführt: 189 Tests, 0 Fehler, 0 fehlgeschlagen, 0 übersprungen; 35 Testsuiten. `clean verify`: BUILD SUCCESS.**

Maven 3.9.9; OpenJDK 21.0.12.1; offizielle Paper-API 1.21.11-R0.1-20260511.115010-91. Da der installierten Laufzeit `ct.sym` fehlt, Source/Target 21 explizit gesetzt; Klassenformat 65 (Java 21).

```sh
mvn -Dmaven.compiler.release= -Dmaven.compiler.source=21 -Dmaven.compiler.target=21 clean verify
```

Zusätzlich zu den vorhandenen Tests: Timer-Alert-only, Hover-Korrektur ohne Cheatverdacht, Inventaröffnungsgrace und verschiedene Bewegungsstichproben, fortbestehende Cancellation nach einem Flag, aktueller Permission-/Toggle-Status bei gebündelten Alerts, paralleler Alert-Burst und Überlast-/Scheduling-Recovery, TPS-/Ping-Datenqualität, ratenbegrenzte Fehlerdiagnose, respektierte Teleportverbote und echte SQLite-Retention mit Transaktion/Audit/Referenzen und Batchgrenze.

Der vorhandene Standard-Fuzztest läuft mit seiner regulären Seedzahl. Die unten historisch genannten 90.000 Verläufe wurden für diesen Fix-Build nicht erneut ausgeführt. Kein laufender Paper-/Folia-Server und kein echter Minecraft-Client wurden getestet; keine gemessene TPS-Last oder Fehlalarm-/Erkennungsrate.

Die mitgelieferten Textberichte in `test-reports/` stammen aus diesem aktuellen Lauf. Folgende ältere Berichte wurden aus den gelieferten Quellen als Historie übernommen; insbesondere die alten Timer-/Hover-Erwartungen beschreiben nicht mehr die aktuellen Maßnahmen.

---

# Testbericht — KYRO AntiCheat 3.7.0

Prüfdatum: 31.10.2026. Build: Linux, JDK 21, Maven 3.9.11, Paper-API 1.21.11 (aus dem offiziellen PaperMC-Quellcode kompiliert, siehe SECURITY-REPORT.md). Klassenformat Java 21.

## Ergebnis

`mvn clean verify`: **160 Tests, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen**. Davon sind 119 die unveränderten Tests aus 3.6.0 und 41 neue Tests für 3.7.

## Vanilla-Physik-Simulator und Fuzzing

Die neuen Bewegungs-Checks wurden gegen eine Nachbildung der Vanilla-1.21-Spielerphysik getestet (`VanillaSim`: Schwerkraft, Luftwiderstand, Boden-/Eis-Reibung, Sprint, Sprint-Sprung, Schleichen, Kopfstoß-Decken, Paper-Filter von 1/16 Block pro Event).

**Legitimes Spiel darf nie abgelehnt werden.** Geprüft wurden:

| Szenario | Ergebnis |
|---|---|
| Springen auf der Stelle (inkl. zusammengefasster Apex-Ticks) | 0 Ablehnungen |
| Dauerhaftes Sprint-Jumping (Bhop) | 0 Ablehnungen |
| Kopfstoß-Sprints (Decke 2 Blöcke), auch auf Eis/Blaueis | 0 Ablehnungen |
| Jump Boost III und Speed II | 0 Ablehnungen |
| 40-Block-Fall, langsames Schleichen über Kanten | 0 Ablehnungen |
| Niedrige Schwerkraft (Attribut) | 0 Ablehnungen |
| Rückstoß mit 3 Ticks Ping-Verzögerung, Explosionen (additiv) | 0 Ablehnungen |
| Item-Benutzung mit Vanilla-Verlangsamung | 0 Ablehnungen |
| Leiter mit Vanilla-Klettergeschwindigkeit | 0 Ablehnungen |
| Client in nicht geladenem Chunk nach Teleport (−0,098/Tick) | 0 Ablehnungen |

**Zufallstest (Fuzzing):** zufälliges Laufen, Sprinten, Schleichen, Drehen, Springen, Decken, Eis, Speed-/Jump-Boost-Wechsel mit Ping-Verzögerung, Abgründe sowie Rückstoß und Explosionen mit zufälliger Ping-Verzögerung (20–300 ms). **90.000 zufällige Spielverläufe mit je 2.000 Ticks (180 Millionen Ticks): 0 Ablehnungen.** Die im Verlauf gefundenen Grenzfälle (Rückstoß kurz nach einer Landung, Kopfstoß in zusammengefassten Ticks, zwei gleichzeitig ausstehende Impulse) wurden behoben und sind als Regression im Test enthalten. Im Standard-Build laufen 400 Verläufe (800.000 Ticks); mehr mit `-Dfuzz.seeds=…`.

**Cheats müssen sofort gestoppt werden:**

| Cheat | Ergebnis |
|---|---|
| Schweben nach dem Sprung-Apex | abgelehnt in Tick 1–2, danach Setback auf Boden |
| Hochfliegen (+0,4/Tick) | ab dem 2. Tick jeder Tick abgelehnt |
| Gleiten (−0,03/Tick) | ≥ 18 von 20 Ticks abgelehnt |
| Luftsprung beim Fallen | sofort abgelehnt |
| Boden-Speed 0,36/Tick (Vanilla-Sprint 0,286) | ≥ 30 von 40 Ticks abgelehnt, nicht aufbaubar |
| Bhop-Speed 0,55/Tick | ≥ 25 von 40 Ticks abgelehnt |
| Step 1 Block | sofort abgelehnt (halbe Stufe erlaubt) |
| NoSlow beim Essen | spätestens im 2. Tick abgelehnt |
| FastLadder 0,45/Tick | ≥ 9 von 10 Ticks abgelehnt |
| Rückstoß zweimal verwenden | zweiter „Boost“ abgelehnt |
| Schweben ohne Bewegungspakete (Fly C) | nach Bestätigung auf den Boden zurückgesetzt |
| Nuker (> 25 Abbau-Starts/s) | überzählige Abbauten abgebrochen, 20/s erlaubt |
| Abbauen durch Wände | Abbau abgebrochen |
| Platzieren gegen nicht sichtbare Blockseite | Platzierung abgebrochen |
| Klickrate > 30 CPS | Treffer ohne Schaden, normale Rate unberührt |
| Inventar-Klicks in voller Bewegung | ab dem 2. Klick abgebrochen |

Zusätzlich laufen die neuen Checks im Integrationstest durch den echten `PlayerMoveEvent`-Handler. Dort wird außerdem geprüft, dass von anderen Plugins abgebrochene Bewegungen die Vorhersage zurücksetzen und dass nur der eigene Setback-Teleport vor fremdem Abbruch geschützt wird.

## Nicht real durchgeführt

Es wurde kein Minecraft-Server gestartet und kein echter Client verbunden. Alle Ergebnisse stammen aus Simulation, Unit- und Integrationstests. Vor dem Live-Einsatz bitte auf einem Testserver prüfen: eigene Knockback-/Dash-/Launchpad-Plugins, Geyser/Floodgate (standardmäßig ausgenommen), Happy-Ghast-Plattformen, Kolben-Maschinen, Elytra und Riptide sowie Spieler mit hohem Ping.

---

# Testbericht — KYRO AntiCheat 3.6.0

Prüfdatum: 31.10.2026. Plattform des Builds: Linux, JDK 21, Maven 3.9.9, offizielle Paper-API 1.21.11-R0.1-SNAPSHOT. Aufgelöste API-Version: 1.21.11-R0.1-20260511.115010-91. Klassenformat Java 21.

## Durchgeführte Prüfungen

`mvn clean verify`: **119 Tests, 0 fehlgeschlagen, 0 Fehler, 0 übersprungen**. Alle Tests wurden tatsächlich ausgeführt. Die folgenden Kategorien unterscheiden bewusst zwischen Simulation, echten SQLite-Operationen und statischer Prüfung.

| Fall | Art | Ergebnis |
|---|---|---|
| Unmögliche Fly-Höhe / andauernder Aufstieg | simulierte Bewegungsdaten | Bewegung wird bereits vor der Alert-Schwelle zur Ablehnung markiert; Setback unabhängig von Flag-ID angefordert |
| Langsames Schweben / vertikale Oszillation | simulierte Zeit- und Bewegungsfolgen | Erkennung nach zusammenhängender Evidenz; keine neue automatische Kick-Sanktion |
| Normale ballistische Sprünge / kurze Apex-Phase | simulierte Vanilla-Bewegung | neue Sofortprävention greift nicht ein |
| Same-tick-Lag-Bursts | simuliert | Burst allein beweist weder Fly-Schweben noch Jesus |
| Jesus mit langsamer Bewegung | simulierte Wassergeometrie und Zeit | erste bestätigte Messphase verhindert, keine gespeicherten Flags nötig |
| RTP-/Velocity-Grace beim Wasserüberqueren | simuliert | vorhandene Ausnahmen verhindern diese neue Prävention |
| Speed | simuliert, zusätzlich echter PlayerMoveEvent mit Testspieler | klares Überschreiten wird sofort zurückgewiesen |
| Wiederholte abgelehnte Bewegungen | echte API-Ereignisse mit Testobjekten | kein Fortschritt der Ziel-/Sync-/Safe-Position; keine illegalen Reach-Historienpunkte |
| Timer, kleine Schritte, Rotation | simulierte Uhr und Bewegungen | Überschuss stoppt Fortschritt; Rotation zählt nicht als Extra-Physiktick; Schuld bleibt erhalten |
| Elytra-Aufstieg über nachgewiesenes Budget | simuliert | Ablehnung vor Buffer/Incident-Schwelle |
| NoFall-Ground-Spoofing | simuliert | bestätigte Manipulation zur Ablehnung markiert |
| NoFall-Rücksetzpunkt | simuliert | Position vor dem Fall statt späterer Landeposition angefordert |
| Mace-Smash / bloß gehaltene Mace | simulierte Serverzustände | nur zeitnah bestätigter Smash erklärt fehlenden Fallschaden |
| BoatFly-Aufstieg / Schweben | simuliert | früher Rücksetzauftrag an ursprünglichen Anker; keine Fortschreibung des Ankers; begrenzte Anfragerate |
| Normales fallendes Boot / Wasserunterstützung / noGravity | simuliert | keine entsprechende Fly-Evidenz |
| Keine sichere Bootposition bekannt | simuliert | keine erfundene Rücksetzung auf den ersten Luftpunkt |
| FastPlace / unzulässige Blockreichweite | simuliert | bestätigte Platzierung wird gecancelt; keine nachträgliche Blockentfernung |
| Normale Platzierung mit 20 Hz | simuliert | keine Cancellation, auch nicht an der Sekundengrenze |
| Global deaktivierte Bewegung/Aktionen | Konfiguration + Simulation | Alerts bleiben möglich, neue Prävention aus |
| Geblockter letzter Anker / ältere sichere Historie | simulierte Validatoren/Futures | geprüfter älterer Anker gewählt, laufende Anfragen zusammengefasst |
| Asynchroner Setback-Abschluss | kontrollierte Futures/EntityScheduler | Zustandsänderung erst auf Spieler-Scheduler |
| Alte Config mit kick:true / auto-ban:true | Konfigurationstest | Normalisierung und CheckConfig lassen automatische Sanktionen nicht zu |
| Kick/Ban/Command-/Loader-API | statischer Scan aller Produktivklassen | geprüfte verbotene API-/Loader-Referenzen nicht vorhanden |
| GUI, Inspektion, Rechte, Follow-Sitzungen | Regressionstests mit Schedulern/Testobjekten | vorhandene Tests bestanden |
| SQLite-Migration, Shutdown, Review und Seiten | echte temporäre SQLite-Datenbanken | vorhandene Integrationstests bestanden |

Die Flag-Sinks in Präventionstests liefern bewusst keine neue ID zurück, entsprechend einer zusammengeführten/unterdrückten Meldung. Damit wird belegt, dass die Präventionsentscheidung nicht eine gespeicherte ID voraussetzt. Der SQLite-Teil testet reale Datenbanken; ein kompletter Live-Server-Ausfalltest der gesamten Pipeline wurde nicht durchgeführt.

## Nicht real durchgeführt

Es wurde kein Minecraft-Server mit akzeptierter EULA gestartet und kein echter Spieler/Client verbunden. Es gab keine realen Paper-/Folia-Regionwechsel, Netzwerkausfälle oder Krypton-/sonstigen Cheat-Client-Versuche. Auch kein realer Geyser/Floodgate-, RTP-, Shop- oder Custom-Knockback-Integrationstest fand statt.

50–300 ms Ping, länger andauernde TPS-Einbrüche, Paketverlust, Stasis-Pearls, Feuerwerk, Wind Charges, Explosionen, Riptide, Slime/Honey, Trapdoors/Treppen/Slabs und alle Plugin-Kombinationen wurden nicht systematisch auf einem laufenden Server getestet. Einige vorhandene Unit-Tests prüfen die entsprechenden Ausnahmezustände; das ist keine Aussage über vollständige Vanilla- oder SMP-Kompatibilität.

## Folia-Prüfung

Statisch geprüft: Spieler-Setback-Abschlüsse auf EntityScheduler, Fahrzeugbearbeitung auf VehicleMoveEvent-Region, revalidierte lokal geladene/regionale Ziele, keine synchronen Chunk-Ladevorgänge und kein direkter Fremdregionszugriff im Setback-Pfad. Fremde Ziele werden abgelehnt. Beibehaltene GUI-/Inspektionstests prüfen geplante Scheduler-Reihenfolgen. Eine echte Mehrregionssimulation oder Laufzeitfreigabe ist damit nicht erfolgt.

## Verbleibende Grenzen

Ein Bukkit/Paper-Ereignisplugin ist kein vollständiger Paket-/Client-Physik-Simulator. »Instant« bedeutet nach festgestellter Evidenz im aktuellen Ereignis; Hover, Wasserlaufen und andere mehrdeutige Vorgänge brauchen weiterhin Messzeit. Völlig ausbleibende Positionsereignisse werden nicht durch einen neu eingeführten Heartbeat erkannt.

Fahrzeugereignisse sind nicht cancellable. Die Wiederherstellung erfolgt asynchron und benötigt einen sicheren gespeicherten Anker in einer lesbaren Region. Keine Zusage gegen alle horizontalen BoatFly-/Beschleunigungsvarianten. Keine Zusage gegen sämtliche NoFall-Varianten; künstlicher Fallschaden wird nicht angewendet. Keine Garantie, dass bereits gestartete Teleports durch Fremdplugins nicht beeinflusst werden.

Unbekannte Geometrie führt weiterhin zu konservativen Ausnahmen, damit fehlende Serverdaten nicht als Cheatbeweis gelten. Vorhandene Bedrock-/Flight-/Staff-Ausnahmen bleiben absichtlich bestehen. Vollständige Erkennung aller Clients, garantierte Vorteilelosigkeit und völlige Fehlerfreiheit werden nicht behauptet.

Vor produktivem Einsatz sind die oben nicht real geprüften Fälle auf deinem Testserver durchzuspielen. Die unveränderten Testquellen und vollständigen Surefire-Berichte befinden sich im Quellenarchiv.
