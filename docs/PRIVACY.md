# Privacy policy - South Tyrol Transit

[English](#english) · [Deutsch](#deutsch) · [Italiano](#italiano)

Last updated: 8 November 2026

## English

South Tyrol Transit is an unofficial, open-source app for public transport in South Tyrol. It is not made by STA, SASA or the Province of Bolzano. It has no user accounts, no advertising, no analytics and no crash reporting, and the developer runs no server: **nothing you do in the app is sent to the developer.**

### What leaves your phone

The app gets its information straight from public open-data services. To do that, it sends them requests, and like any web request each one carries your phone's IP address:

| Service | What the app sends | When |
| --- | --- | --- |
| STA journey planner (`efa.sta.bz.it`) | The text you search for, the stops or places of a route, the time, the app's language. **Your location**, only when you use a "my position" feature: nearby stops, a route from where you are, the address of a point. | When you search, plan a route, open a stop's departures, or use a widget |
| Open Data Hub (`opendatahub.com`) | Requests for the timetable, live times, vehicle positions, parking, charging and bike data. No location and no search text. | When the app or a widget needs current data, and for the timetable download |
| STA FTP server (`ftp.sta.bz.it`) | A request for the timetable file | Only if the Open Data Hub download fails and the fallback is on in Settings |
| Map tiles: OpenFreeMap (`openfreemap.org`) and the Province of Bolzano's map service (`geoservices.buergernetz.bz.it`) | Requests for the map images of the area you look at | When a map is shown |
| GitHub (`api.github.com`), GitHub version only | A request for the newest release | When the app checks for updates. The Google Play version does not do this |

These services are run by others under their own privacy policies. The app does not add any identifier to these requests: it sends no account, device ID or advertising ID, and its user agent only names the app.

### Your location

Location is optional. The app asks for it only when you first tap a feature that uses it, and you can refuse or withdraw it in Android's settings at any time. When you follow a journey, your position is used on the phone only, to tell you whether you are on track; it is not sent anywhere.

### What stays on your phone

Saved stops, lines and places, recent searches, settings and the downloaded timetable are stored only on your phone. On Android 9 and later, Android's own backup may include your saved items and settings in your Google account's end-to-end encrypted backup, if you have backup turned on. You can delete everything by clearing the app's data or uninstalling it.

### Notifications

If you turn on alert notices, the app checks for service alerts itself and shows them on your phone. No push service is involved and nothing about your saved stops or lines leaves the phone for this.

### Children

The app is not directed at children and collects no data from anyone.

### Changes and contact

Changes to this policy are published on this page, with a new date at the top. Questions: open an issue at [github.com/ta-samuelece/SouthTyrolTransit/issues](https://github.com/ta-samuelece/SouthTyrolTransit/issues).

## Deutsch

South Tyrol Transit ist eine inoffizielle Open-Source-App für den öffentlichen Verkehr in Südtirol. Sie stammt nicht von STA, SASA oder dem Land Südtirol. Sie hat keine Benutzerkonten, keine Werbung, keine Analyse und keine Absturzberichte, und der Entwickler betreibt keinen Server: **Nichts, was du in der App tust, wird an den Entwickler gesendet.**

### Was dein Telefon verlässt

Die App holt ihre Informationen direkt bei öffentlichen Open-Data-Diensten. Dazu sendet sie ihnen Anfragen, und wie jede Webanfrage enthält jede die IP-Adresse deines Telefons:

| Dienst | Was die App sendet | Wann |
| --- | --- | --- |
| STA-Fahrplanauskunft (`efa.sta.bz.it`) | Den Suchtext, die Haltestellen oder Orte einer Verbindung, die Zeit, die Sprache der App. **Deinen Standort** nur, wenn du eine „Mein Standort“-Funktion nutzt: Haltestellen in der Nähe, eine Verbindung von dort, wo du bist, die Adresse eines Punktes. | Wenn du suchst, eine Verbindung planst, die Abfahrten einer Haltestelle öffnest oder ein Widget nutzt |
| Open Data Hub (`opendatahub.com`) | Anfragen nach Fahrplan, Echtzeiten, Fahrzeugpositionen, Park-, Lade- und Fahrraddaten. Kein Standort und kein Suchtext. | Wenn die App oder ein Widget aktuelle Daten braucht, und für den Fahrplan-Download |
| FTP-Server der STA (`ftp.sta.bz.it`) | Eine Anfrage nach der Fahrplandatei | Nur wenn der Download vom Open Data Hub scheitert und der Ausweichweg in den Einstellungen an ist |
| Kartenkacheln: OpenFreeMap (`openfreemap.org`) und der Kartendienst des Landes Südtirol (`geoservices.buergernetz.bz.it`) | Anfragen nach den Kartenbildern des Gebiets, das du ansiehst | Wenn eine Karte angezeigt wird |
| GitHub (`api.github.com`), nur GitHub-Version | Eine Anfrage nach der neuesten Version | Wenn die App nach Updates sucht. Die Google-Play-Version tut das nicht |

Diese Dienste werden von anderen betrieben, nach deren eigenen Datenschutzbestimmungen. Die App fügt diesen Anfragen keine Kennung hinzu: Sie sendet kein Konto, keine Geräte-ID und keine Werbe-ID, und ihr User-Agent nennt nur die App.

### Dein Standort

Der Standort ist freiwillig. Die App fragt erst danach, wenn du zum ersten Mal eine Funktion antippst, die ihn nutzt, und du kannst ihn jederzeit in den Android-Einstellungen verweigern oder entziehen. Wenn du einer Verbindung folgst, wird deine Position nur auf dem Telefon genutzt, um dir zu sagen, ob du auf Kurs bist; sie wird nirgendwohin gesendet.

### Was auf deinem Telefon bleibt

Gespeicherte Haltestellen, Linien und Orte, letzte Suchen, Einstellungen und der heruntergeladene Fahrplan werden nur auf deinem Telefon gespeichert. Ab Android 9 kann Androids eigene Sicherung deine gespeicherten Einträge und Einstellungen in die Ende-zu-Ende-verschlüsselte Sicherung deines Google-Kontos aufnehmen, wenn du die Sicherung eingeschaltet hast. Du kannst alles löschen, indem du die Daten der App löschst oder sie deinstallierst.

### Benachrichtigungen

Wenn du Meldungen einschaltest, sucht die App selbst nach Störungsmeldungen und zeigt sie auf deinem Telefon an. Dabei ist kein Push-Dienst beteiligt, und nichts über deine gespeicherten Haltestellen oder Linien verlässt dafür das Telefon.

### Kinder

Die App richtet sich nicht an Kinder und erhebt von niemandem Daten.

### Änderungen und Kontakt

Änderungen dieser Datenschutzerklärung werden auf dieser Seite veröffentlicht, mit neuem Datum oben. Fragen: Eröffne ein Issue unter [github.com/ta-samuelece/SouthTyrolTransit/issues](https://github.com/ta-samuelece/SouthTyrolTransit/issues).

## Italiano

South Tyrol Transit è un'app non ufficiale e open source per il trasporto pubblico in Alto Adige. Non è realizzata da STA, SASA o dalla Provincia di Bolzano. Non ha account utente, pubblicità, analisi né segnalazioni di crash, e lo sviluppatore non gestisce alcun server: **niente di ciò che fai nell'app viene inviato allo sviluppatore.**

### Cosa lascia il tuo telefono

L'app prende le informazioni direttamente da servizi pubblici di open data. Per farlo invia loro delle richieste e, come ogni richiesta web, ognuna contiene l'indirizzo IP del tuo telefono:

| Servizio | Cosa invia l'app | Quando |
| --- | --- | --- |
| Orario STA (`efa.sta.bz.it`) | Il testo che cerchi, le fermate o i luoghi di un percorso, l'orario, la lingua dell'app. **La tua posizione** solo quando usi una funzione "la mia posizione": fermate vicine, un percorso da dove ti trovi, l'indirizzo di un punto. | Quando cerchi, pianifichi un percorso, apri le partenze di una fermata o usi un widget |
| Open Data Hub (`opendatahub.com`) | Richieste di orario, tempi in tempo reale, posizioni dei veicoli, dati su parcheggi, ricarica e bici. Nessuna posizione e nessun testo di ricerca. | Quando l'app o un widget hanno bisogno di dati aggiornati, e per scaricare l'orario |
| Server FTP della STA (`ftp.sta.bz.it`) | Una richiesta del file dell'orario | Solo se il download dall'Open Data Hub non riesce e l'alternativa è attiva nelle Impostazioni |
| Tessere della mappa: OpenFreeMap (`openfreemap.org`) e il servizio cartografico della Provincia di Bolzano (`geoservices.buergernetz.bz.it`) | Richieste delle immagini della mappa della zona che guardi | Quando viene mostrata una mappa |
| GitHub (`api.github.com`), solo versione GitHub | Una richiesta dell'ultima versione | Quando l'app cerca aggiornamenti. La versione di Google Play non lo fa |

Questi servizi sono gestiti da altri, secondo le loro informative sulla privacy. L'app non aggiunge identificativi a queste richieste: non invia account, ID del dispositivo né ID pubblicitario, e il suo user agent indica solo l'app.

### La tua posizione

La posizione è facoltativa. L'app la chiede solo quando tocchi per la prima volta una funzione che la usa, e puoi rifiutarla o revocarla in qualsiasi momento nelle impostazioni di Android. Quando segui un viaggio, la tua posizione è usata solo sul telefono, per dirti se sei sulla strada giusta; non viene inviata da nessuna parte.

### Cosa resta sul tuo telefono

Fermate, linee e luoghi salvati, ricerche recenti, impostazioni e l'orario scaricato restano solo sul tuo telefono. Da Android 9, il backup di Android può includere gli elementi salvati e le impostazioni nel backup crittografato end-to-end del tuo account Google, se hai attivato il backup. Puoi cancellare tutto eliminando i dati dell'app o disinstallandola.

### Notifiche

Se attivi gli avvisi, l'app cerca da sola gli avvisi di servizio e li mostra sul telefono. Non è coinvolto alcun servizio push e, per questo, nulla sulle tue fermate o linee salvate lascia il telefono.

### Bambini

L'app non è rivolta ai bambini e non raccoglie dati da nessuno.

### Modifiche e contatti

Le modifiche a questa informativa vengono pubblicate su questa pagina, con una nuova data in alto. Domande: apri una issue su [github.com/ta-samuelece/SouthTyrolTransit/issues](https://github.com/ta-samuelece/SouthTyrolTransit/issues).
