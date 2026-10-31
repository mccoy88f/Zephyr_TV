# Changelog

Zephyr TV shows the section for its own version in **Zephyr TV settings → Updates** after an update.
The build stops if `versionName` has no section here.

Write every entry in ASD-STE100 Simplified Technical English:

- Write one idea in each sentence. Use 20 words or fewer in each sentence.
- Use the active voice and the present tense.
- Write instructions as commands: "Select **Updates**", not "**Updates** can be selected".
- Use one word for one thing. Use the names that Zephyr TV shows on the screen.
- Start each entry with "- ". Zephyr TV shows each entry as one item. It removes `**` marks.

Put changes that are not released under `## Unreleased`. Rename that heading to the version number when you release.


## 3.6.0

- The page of a film, series, season, episode or TV channel is now a page over the whole screen.
- On a title's page, action buttons are on the right. Details, description, directors and cast are on the left.
- Directors and actors show round avatar pictures from TMDB. Select a director or actor to search their titles.
- In **Updates**, when you turn off **Receive betas**, Zephyr TV offers the newest non-beta release available.
- Zephyr TV installs on Android 7.0 and 7.1, for example on older boxes and on Fire TV.
- On Android 7.0, add-ons and extensions with Let's Encrypt certificates connect reliably.

## 3.6.0-beta.3

- On a title's page, action buttons are on the right. Details, expanded description, director and cast are on the left.
- Left on a button goes to the description. Right on the description goes back to the buttons.

## 3.6.0-beta.2

- The page of a film, series, season, episode or TV channel is now a page over the whole screen, not a panel at the side. The picture of the title fills the screen. On the left are its logo, what it is about and its buttons.
- A TV channel's page shows the logo of the channel large on the right.

## 3.6.0-beta.1

- Zephyr TV installs on Android 7.0 and 7.1 too, for example on older boxes and on Fire TV with Fire OS 6. Before, it needed Android 8.0.
- On Android 7, **Continue Playing** shows the films, episodes and channels that you watch in Zephyr TV. Android 7 has no Watch Next, so the cards of other apps do not show.
- On Android 7, the apps have no rows of their own (for example the Recommended row of YouTube). Other apps publish these rows only from Android 8.
- On Android 7, the apps show their normal icons in greyscale, because Android 7 has no adaptive icons.
- On Android 7, an update asks you to turn on **Unknown sources** in **Security**, one time.
- On Android 7.0, the add-ons, plugins and extensions on servers with Let's Encrypt certificates connect. Zephyr TV has the Let's Encrypt root certificates, which Android 7.0 does not have.

## 3.5.6

- New: **Visible rows**, on the page of each catalog in **Content catalog**. It lists the rows of the catalog. Turn each row on or off. A row that is off does not show under Films, TV Series or the app of the catalog.

## 3.5.5

- On the page of a film, series or episode, you can go down with the remote control to the trailer and the buttons of the apps. In 3.5.4, they did not get the focus.

## 3.5.4

- The player plays Blu-ray files (.m2ts). Before, it showed "The device can't play this format" for them.
- On a TV, the player decodes the sound formats that the TV can't decode, for example Dolby TrueHD. Before, these videos played without sound.
- In the list of streams in the player, the group "Quality not stated" is last.
- When you choose the stream yourself, the list shows the streams in groups by quality, as the player does. The group "Quality not stated" is last.
- On the page of a film, series or episode, the trailer and the buttons of the apps are at the bottom, after the other rows.

## 3.5.3

- On a phone or tablet, the ← and ✕ buttons of the side panels have their arrow and cross in the middle.
- In the Glass theme, the ← and ✕ buttons are panes of glass, as the cards are.
- In **Hide your IP (Cloudflare WARP)**, the row **VPN** is now **VPN status (any app)**. It tells whether a VPN is on, from Zephyr TV or from another app.

## 3.5.2

- While the player loads, the last line (Loading, Checking the streams…) fades in and out slowly. It stops when the video plays.
- When you skip forward or back and the video loads again, the loading screen shows with "Loading" until the video plays. Before, the picture stayed still with no sign.

## 3.5.1

- Zephyr TV opens the nuvio:// links of Nuvio too: the page of a film or series, and add-ons. The watch parties of Nuvio stay in Nuvio.

## 3.5.0

- Zephyr TV works on Android phones and tablets too. It has an icon among their apps, and it uses one APK for TVs, phones and tablets.
- With a finger, scroll the home screen up, down and to the side. Tap an app to show its rows, and tap again to open it. An app with no rows opens with one tap. Pull the home screen down to read the rows again.
- Swipe Spotlight to the side to see the title before or after. Dots show where you are.
- The side panels have ← at the top left and ✕ at the top right. A tap beside a panel closes it.
- On a phone or tablet, what a TV does with a QR code and a phone is done on the device itself: with its keyboard, its file picker and its Save dialog.
- In the player, tap for the information, double-tap a side to go back or on 10 seconds, and drag the progress bar. Swipe on the left for the brightness and on the right for the volume. Pinch to fill the screen. 🔒 locks the controls. A video goes on in a small window when you leave the player.
- On a phone or tablet, Dolby and DTS sound plays.
- At the end of an episode, the next one starts after 10 seconds.
- When you come back from the player, the page of the film or channel shows again with the focus on the row you selected.
- The page of an episode shows what that episode is about. It uses the English description of TMDB when there is none in your language.
- In the lists of streams, each stream has two lines: what it is, and its source in italics.
- New: **Hide your IP (Cloudflare WARP)**, in **Extra features**. With **WARP for Zephyr TV** on, Zephyr TV goes through the free WARP network of Cloudflare, and the other apps do not.
- New: links. **Share** on the page of a film, series or episode sends a link. Zephyr TV opens ztv:// links, stremio:// links and the addresses of Stremio add-ons, also when they are shared to it. It adds an add-on only after you confirm it.
- The TV inputs button does not show on a device without inputs.
- When the home screen starts, the focus is on the Play queue.
- The texts say "device" where they said "TV" for the device that Zephyr TV runs on.

## 3.4.0-beta.13

- The texts of Zephyr TV say "device" where they said "TV" for the device it runs on, as it runs on phones and tablets too. For example: "Streams in the device's language first", "This device has no voice search".
- "TV" stays where it means TV: TV channels, TV guide, TV inputs, Watch on TV.

## 3.4.0-beta.12

- On a phone or tablet, in the player, swipe up or down on the left for the brightness and on the right for the volume. In the middle, on a TV channel, a swipe changes the channel.
- On a phone or tablet, pinch the picture to fill the screen, or to fit it again.
- On a phone or tablet, the player has a 🔒 button that locks the controls. Tap the picture, then 🔓, to unlock them.
- On a phone or tablet, when you leave the player while a video plays, it goes on in a small window (picture-in-picture).
- At the end of an episode, the next one starts after 10 seconds. You can start it at once, or stay.
- On a phone or tablet, an app that has no rows opens with one tap.
- On a phone or tablet, pull the home screen down from the top to read the rows of the app again.
- On a phone or tablet, Spotlight shows dots, or a count, for its titles. Taps and long presses vibrate a little.
- On a tablet, a row shows more cards.
- On a phone or tablet, the side panels have ← at the top left, to go to the page before, and ✕ at the top right.
- New: links. **Share** on the page of a film, series or episode sends a link: Zephyr TV opens it on another phone or tablet.
- Zephyr TV opens ztv:// links, stremio:// links and the addresses of Stremio add-ons, also when they are shared to it from another app. It adds an add-on or a repository only after you confirm it. An extension shows what it is before it installs.
- On a phone or tablet, hold the Zephyr TV icon for **Search** and **Play queue**.

## 3.4.0-beta.11

- On a phone or tablet, when you come back from the player, the home screen stays as you left it: the page that was open (for example the page of a film) and the app or virtual app you selected. Before, the home screen could start again from the beginning.

## 3.4.0-beta.10

- The page of an episode shows what that episode is about. Before, it could show the description of the series.
- When TMDB has no description of an episode in the language of the TV, and the catalog has none, Zephyr TV shows the English one of TMDB.
- The pages of a season and of an episode show again when the names and descriptions in your language arrive.
- When there is no description of an episode, the page says so.

## 3.4.0-beta.9

- New: **WARP for Zephyr TV**, in **Hide your IP (Cloudflare WARP)**. Zephyr TV, and only Zephyr TV, goes through the free WARP network of Cloudflare: the sites and the servers of the videos see an address of Cloudflare and not yours. The 1.1.1.1 app is not necessary.
- Android asks one time to allow the VPN. When you start Zephyr TV again, WARP starts again.
- In the lists of streams, the source of each stream is in italics, not in violet.
- On a phone or tablet, Dolby and DTS sound plays in the versions downloaded from the releases too. Before, the decoder could be left out of them.

## 3.4.0-beta.8

- In the list of streams (with **Stream choice** set to **Manual**, and in the player), each stream has two lines. The top line tells what the stream is, and it scrolls when it is too long. The bottom line tells its source, in italics.

## 3.4.0-beta.7

- New: **Hide your IP (Cloudflare WARP)**, in **Extra features**. It says whether a VPN is on, and opens Cloudflare WARP or its Play Store page.
- With WARP on, the sites that add-ons, plugins and extensions read, and the servers of the videos, see an address of Cloudflare and not yours.

## 3.4.0-beta.6

- On a phone or tablet, the status bar of the device (time, network) does not show over the home screen. Swipe down from the top to see it for a moment.
- To show it always, turn on **Phone status bar** in **Appearance & wallpapers**.

## 3.4.0-beta.5

- When you come back from the player to the page of a film, the focus is on the row you selected: **Watch** or **Watch on**. Before, it was on the description.
- The page of a TV channel stays open while the channel plays. When you come back, it shows again with the focus on **Watch**, also from the search results.

## 3.4.0-beta.4

- On a phone or tablet, a tap on the progress bar of the player goes to that point. Before, it went to the middle.
- When the home screen starts, the focus is on the Play queue, not on Spotlight.
- On a phone or tablet, a swipe on the Favourites row only scrolls it. Before, it selected an app by itself, and its rows loaded under it.

## 3.4.0-beta.3

- On a phone or tablet, the player plays Dolby (AC3, E-AC3) and DTS sound. Before, these films and channels had no sound there.
- On a phone or tablet, a PIN page (layout lock, 18+ lock) opens the number keyboard of the device. Tap the boxes to show it again.
- On a phone or tablet, each side panel has an X at its top left to close it. A tap beside the panel closes it too. The audio and subtitles panel of the player has an X too.
- On a phone or tablet, the home screen goes back up when the rows under the apps get fewer. Before, after you scrolled down the rows of one app and tapped an app with fewer rows, the top of the screen stayed out of view.
- On a phone or tablet, Spotlight follows your finger when you swipe it to the side. Tap its title to show its buttons. Tap it again to show all of the description.

## 3.4.0-beta.2

- On a phone or tablet, the rows lower on the home screen react to a tap and scroll to the side. Before, only the first rows did.
- On a phone or tablet, what a TV does with a QR code and a phone is done on the device itself:
  - To type an address, a key or a name, use the keyboard of the device.
  - To add a photo, a backup or an extension file, select **Choose a file**.
  - To save a backup, select **Save the file**.
- On a phone or tablet, the rows that offer to type something on a phone do not show, and the search has no QR code.

## 3.4.0-beta.1

- Zephyr TV works on Android phones and tablets too. It has an icon among their apps.
- With a finger, swipe up and down to scroll the home screen. Swipe the rows to the side.
- Tap an app once to show its rows under it. Tap it again to open it.
- Swipe Spotlight to the side to see the title before or after. Tap the title to show its buttons.
- In the player, tap the picture to show or hide the information. Tap the buttons to use them.
- On a film or an episode, double-tap the left or right side of the picture to go back or on 10 seconds. Drag the progress bar to go to another point.
- On a TV channel, swipe up or down to go to the next or the previous channel.
- Zephyr TV turns with the device, in both landscape positions.
- The TV inputs button in the top bar does not show on a device without inputs, for example a stick, a phone or a tablet.

## 3.3.4

- A YouTube video or live of an extension (for example a webcam) opens on its page in the YouTube app. Before, the YouTube app opened on its home screen.

## 3.3.3

- The standard documents the extension repositories (`repository-zephyrtv.json`). Zephyr TV's own repository is now online: OMG Premium TV and Live Webcams are suggested again in **Zephyr Extensions**.
- A TV channel or webcam that plays in the YouTube app no longer closes the player. Before, the player closed and showed the home screen again.
- When the player can't look for the streams, it says so. It doesn't close.

## 3.3.2

- While a search is open, selecting search opens the panel with its words and filters kept.

## 3.3.1

- In search filters, **Type of content** offers every available type: TV channels, Radio, Videos, Podcasts, and more.
- When 18+ content shows, its titles and channels appear in search results under their own **Adult content** row.
- In search filters, **Adult content → Only adult content** shows all adult titles found.

## 3.3.0

- In **Extra features**, the **Extensions** entry is now **Zephyr Extensions**.
- A film that is still only in cinemas in your country has a small **In cinemas** tag in the bottom-right corner of its card. The information comes from TMDB (the key is necessary).
- New setting: **Content → Hide films not yet released** (off by default). It hides from Spotlight and the apps' rows the films that are not out at home yet: still only in cinemas, or still to come. A film is hidden only when TMDB says so for sure.
- A film or episode with Dolby or DTS sound no longer stops after a skip forward or back. On some TVs, such sound didn't start again after a skip, so the picture stayed still. Now the player starts the stream again at the new position.

## 3.2.0

- The page of a film or an episode looks for the streams when it opens. **Watch** shows how many streams there are, and the player starts with them. When no source has streams, the page says so before the player opens.
- A TV channel's streams are found when its card stays selected for a moment. A channel made of several (for example HD and SD) has all its streams ready when it starts.
- The streams found stay ready for 90 seconds, for the launcher and the player. A search that is still going on is not done again.
- Subtitles are off by default. The player shows only the subtitles that a stream forces.
- New setting: **Content → Subtitles always on**. A subtitle choice made in the player stays until the player closes.

## 3.1.1

- When a channel, film or episode starts to play, the player shows its information banner for 5 seconds, as **OK** does.
- The player plays HLS streams with WebVTT subtitles again. Since 3.0.0, these streams stopped with "No streams available".
- In Spotlight, the play mark of **Watch** shows when the button has the focus. Before, it was white on the white button.

## 3.1.0

- The backup keeps more settings: the TV guide address, the channel logos from iptv-org, and how the HDMI inputs show.
- The backup keeps the data of each user: the titles watched and **Continue Playing**. Restore puts them back. The Trakt account is not in the backup: sign in again from your phone after a restore.

## 3.0.0

- New: **Extensions**, in **Settings → Extra features → Extensions**. Extensions add films, series, TV channels, radio stations and videos. They run on the TV, apart from everything else.
- Add an extension from the address of its manifest-zephyrtv.json, or send its .zip file from your phone. **OMG Premium TV** is suggested in the list: select it to install it.
- An extension with content of its own has its own app on the apps row. **Where it shows** can put its content in the collectors instead: Films, TV Series, TV Channels, Radio, Videos, Courses, Podcasts, Audiobooks and Events.
- Each extension has **Options**, with lists of items such as playlists and accounts. Type the fields of an item on your phone in one step.
- **Add-ons and Plugins** is now **Extra features**. **Virtual apps and integrations** holds where TV channels, films, TV series, radio stations and adult content show. Films and TV Series are two settings.
- Radio stations show apart from TV channels and play at once. While one plays, its logo, its name and the song on air show on the screen.
- The player plays more channels: channels with their sound out of step with their picture, channels with their sound in a stream of its own, and rtsp://, rtmp:// and udp:// streams. A stream that stops loading starts again by itself. When the TV cannot play the sound, the picture plays alone.
- When no stream works, the player tells why.
- Extensions and Nuvio plugins run in separate processes. When one stops responding, Zephyr TV stops it and starts it again.
- The posters, backdrops and logos of films and series are kept on the TV after the first download. Rows, search results and title pages show them at once, also after Zephyr TV restarts, and Zephyr TV does not download them again.
- The player uses the channel logos that the home screen already keeps, so the logo shows at once while a channel loads.

## 3.0.0-beta.8

- A stream that played and then stops loading, for example after a network drop, starts again by itself, up to two times. Before, the player went to the next stream or said that no stream works.
- Streams at rtsp://, rtmp:// and udp:// addresses play. Before, they were left out of the list.
- When the TV cannot play a channel's sound, the channel plays with the picture only, and a message tells it.
- A server that refuses the player now gets a second try as VLC, when the playlist gives no User-Agent of its own.
- When no stream works, the player tells why: the server's certificate is not valid, the server refuses access, the stream no longer exists, the server does not answer, the format is not supported, or the stream does not start.

## 3.0.0-beta.7

- Channels with their sound in a stream of its own, such as Radio 105 TV and Virgin Radio TV, stopped after a few seconds since 3.0.0-beta.4. Now they play.
- **Virtual apps and integrations** has **Films** and **TV Series** as two settings: each can show in its own virtual app, in an app for each add-on, or nowhere. A setting made before applies to both.
- New: **Radio**, in **Virtual apps and integrations**. By default the radio stations of every extension show in the **Radio** virtual app. You can keep them in the app of each extension, or switch them off.
- **Check for updates** in **Extensions** tells which extensions it could not check or update, instead of saying that there are no updates.

## 3.0.0-beta.6

- While a radio station plays, the song on air shows under its name, when the station sends it. The song changes when the station sends a new one.

## 3.0.0-beta.5

- Radio stations in M3U playlists (radio="true") show apart from the TV channels, in rows of their own. Select one to play it at once. Its logo and name stay on the screen while it plays.
- With **Where it shows** set to the collectors, an extension's radio stations go to **Radio**.

## 3.0.0-beta.4

- Some live channels have their sound hours away from their picture, for example RTL 102.5 TV in some playlists. The player stayed on loading and then showed that no stream works. Now the player moves the sound onto the picture, and the channel plays.
- While a channel loads, its logo shows in a wide box, as on the channel cards.

## 3.0.0-beta.3

- OMG Premium TV no longer comes inside Zephyr TV. **Extensions** suggests it: select it to install it from its address. Zephyr TV installs the latest version.
- You can remove every extension. A suggested extension that you remove shows as suggested again.

## 3.0.0-beta.2

- New: **Extensions**, in **Settings → Extra features → Extensions**. Add an extension from the address of its manifest-zephyrtv.json, or send its .zip file from your phone. Zephyr TV shows what the extension is before it installs it.
- An extension with content of its own has its own app on the apps row, with its logo. Its channels, films and series are in that app.
- **Where it shows** puts the content of an extension in the collectors instead: Films, TV Series, TV Channels, Radio, Videos, Courses, Podcasts, Audiobooks and Events.
- Each extension has **Options**. Lists, such as playlists or accounts, have items that you can add, change, move and remove. Type the fields of an item on your phone in one step.
- New: **OMG Premium TV** comes with Zephyr TV. Add your M3U playlists or Xtream accounts in its **Options**. Its channels show with their groups, logos and TV guide.
- The section **Add-ons and Plugins** is now **Extra features**. **Virtual apps and integrations** holds the settings for where channels, films and adult content show.
- Extensions and Nuvio plugins run in separate processes. When one stops responding, Zephyr TV stops it and starts it again.

## 3.0.0-beta.1

- First beta of Zephyr TV 3. Version 3 adds **Extensions**, a new kind of add-on made for Zephyr TV. This beta prepares them. You cannot install extensions yet.
- Nuvio plugins run in a separate process. A plugin that stops responding or crashes no longer affects the home screen or the player. Zephyr TV stops it and starts it again.
- The documentation for extension developers is in the Zephyr TV repository, in docs/extensions.md.

## 2.2.2

- The cards of the add-ons and the search results show the pictures of the main catalog, as the other catalogs do. A title looks the same everywhere.
- When the main catalog has no picture for a title, the card and the title page show the picture of its add-on or plugin.

## 2.2.1

- Nuvio plugins can keep data from one run to the next, for example a token that expires. Zephyr TV removes this data when you remove the repository.
- **Options available** shows the notes of the plugin at the top of the page.

## 2.2.0

- New: **Options available**, under each Nuvio plugin that has options of its own. Set its switches, choices and texts there. Type a text with the remote, or paste it from your phone.
- New: **Order of the plugins**, on the page of a Nuvio repository. Select a plugin with OK, then select the place for it with OK. Zephyr TV asks the plugins for streams in this order.
- In the rows of the other catalogs in **Content catalog**, the cards show the pictures of the main catalog. A title looks the same in every row.
- The page of Nuvio plugins tells that sources that need WebSocket do not work.

## 2.1.3

- In the player, **Stream** groups the streams by quality, the highest first.
- **Continue Playing** starts a film or episode with the stream it played before, without looking for others. When that stream no longer plays, the player looks for a new one.
- With **Quick start**, when no source has a stream in the preferred quality, the player waits for all the sources, up to 30 seconds, and starts the best one a step below.
- Each stream starts with the default audio: in the language of the TV, when the stream has it. The audio you choose is for that stream only, and does not carry over to the next stream.

## 2.1.2

- New: **Guide**, in **Settings → System**. The user guide, chapter by chapter, in Italian on a TV in Italian and in English in every other language.
- The cards in **Continue Playing** show the wide picture of the film or series with its logo, as the Films and TV Series rows do, instead of its poster.
- When you lock 18+ content, Zephyr TV goes back to the home screen with focus on the first app, Continue Playing. The same happens when 18+ locks again by itself, for example after standby.

## 2.1.1

- Before you choose an audio language in the player, it plays the audio in the language of the TV, when the stream has it. Before, it played the stream's default audio, often English.

## 2.1.0

- The Settings section is now **Add-ons and Plugins**. Its **TV Channels**, **Films and TV Series** and **Adult Content** have the same choices: **System TV app** (TV channels only), **Virtual app**, **One app per add-on**, and **With the others** or off.
- **Adult Content** can now give each adult add-on an app of its own.
- When you choose **Virtual app**, the app goes into your favourites. You can take it out again.
- New: **Quick start**, in **Content**, on by default. A film or episode starts with the first stream in the preferred quality that works, without waiting for all the add-ons and plugins. The others keep looking, and their streams join the list in the player.
- **Streams in the TV's language only** is now **Streams in the TV's language first**. The streams in the language of the TV come first, then those that say no language, then those in other languages, as a last choice. They are no longer hidden.
- **Hide streams without a language** is now off by default.
- When no stream in the language of the TV has come yet, the player waits for all the add-ons and plugins before it plays a stream in another language, or one that says no language. Quick start plays only a stream in the language of the TV.
- Quick start needs a preferred quality. With **Highest**, the player waits for all the add-ons and plugins, up to 30 seconds, and then starts the best stream. The same happens when quick start is off.

## 2.0.1

- A title page no longer asks the add-ons and plugins for streams when it opens. The player asks them when you select **Watch**, or the streams page does when **Stream choice** is **Manual**.
- The player says what it does: **Loading the available streams…**, then **Checking which sources work…**, then **Loading stream 1 of 5…**.
- The **Watch on** rows with the number of streams are gone. Set **Stream choice** to **Manual** to pick the source.
- New: **Streams in the TV's language only**, in **Content**, on by default. Films and series show and play only the streams in the language of the TV. Plugins for other languages are not asked.
- New: **Hide streams without a language**, in **Content**, under **Streams in the TV's language only**, on by default. A stream from a plugin that says no language counts in the plugin's languages.
- The player tries the streams with the highest quality first, among those that work. In the manual list, each source's streams come with the highest quality first.
- In Settings, Back goes to the row you left the page from. Before, focus could land on another row.
- New: **Preferred quality**, in **Content**, under **Stream choice**: **Highest** by default, or 2160p, 1440p, 1080p, 720p, 480p. With 1080p, for example, the player tries 1080p and lower first, then the higher ones. The 4K streams stay in the list.
- **TV channels**, **Films and series** and **Adult add-ons** are now on the **Add-ons and plugins** page, for add-ons and plugins alike. **Stremio add-ons** has the list of add-ons and **Add an add-on**.

## 2.0.0

- New: **Nuvio plugins**, in **Add-ons and plugins**. Add a plugin repository from your phone or with the remote. Each plugin has its own switch.
- The plugins find streams for films, series and TV channels. Their streams show as **Watch on** rows on the title page, and the player uses them too.
- You can switch off an add-on or a plugin repository, and move a repository up or down.
- The player checks all the streams of a title at the same time, and starts with those that answer with a video. It waits for slow plugins, and skips dead links and web pages.
- New: **Stream choice**, in **Content**. Set it to **Manual** to pick the stream yourself from one list of all the add-ons and plugins.
- For series, the player has **Previous episode** and **Next episode**. At the end of a season, it asks if you want to start the next season.
- A title page shows what the title is about at once, before its other details load.
- A search with filters and no words looks in all the catalogs of **Content catalog**, the main catalog first.
- When a stream fails, **Settings → System → Problems** shows its source, its server and what the server sent.

## 2.0.0-beta.5

- The player checks all the streams of a film or episode at the same time, and tries first those that answer with a video. Dead links and web pages no longer make you wait.
- The player no longer misses the streams of slow plugins. It starts with the first streams, and adds the others when they come. If all the streams so far fail, it waits for the others and shows "Looking for more streams…".
- Web pages that some plugins give as streams are skipped.
- More Nuvio plugins work, for example DVDPlay and MalluMV.
- When a stream fails, Settings → System → Problems shows its source, its server and what the server sent.

## 2.0.0-beta.4

- In the player, the lines of the stream, the audio and the subtitles under the progress bar scroll when they are too long.
- A title page shows what the title is about at once, before its other details load. Its episodes in your language come after, and focus stays where it is.
- Plugin streams written into their address (MovyITA, VixSrc) now play: Zephyr TV reads what they are from their first lines.
- A stream you pick by hand plays even if the check before it fails. Some servers refuse only the check.
- Zephyr TV no longer passes to the player the headers it sets itself, such as Accept-Encoding. With some plugins they stopped the stream.

## 2.0.0-beta.3

- In the player, a series can go back to the episodes before the one you started, not only on to the next ones.
- The player has **Previous episode** and **Next episode** buttons for series. The remote's previous and next keys do the same.
- A series stops at its first and last episode. It does not go round.
- At the end of the last episode of a season, the player asks if you want to watch the first episode of the next season.
- New: **Stream choice**, in **Content**. Set it to **Manual** to pick the stream yourself: **Watch** shows the streams of all the add-ons and plugins in one list, with the source of each.
- A name that is too long for its row scrolls when the row has focus, for example the name of a stream.

## 2.0.0-beta.2

- A search with filters and no words finds titles again with every catalog. Before, with Nuvio Catalog as the main catalog, it found nothing.
- A search with filters and no words looks in all the catalogs of **Content catalog**, the main catalog first.
- A search for a year and a genre together finds more titles.
- The genres in the search filter are those of the main catalog.

## 2.0.0-beta.1

- New: **Nuvio plugins**. Settings has **Add-ons and plugins**, with **Stremio add-ons** and **Nuvio plugins**.
- Select **Add a repository** to add a Nuvio plugin repository, from your phone or with the remote. Each plugin has its own switch.
- The plugins find streams for films, series and TV channels. Their streams show as **Watch on** rows on the title page, after the add-ons, and the player uses them too.
- Mark a repository 18+ to show its plugins only when 18+ content is unlocked.
- Zephyr TV reads each repository again once a day, for new plugins and new versions.
- Plugins that use cheerio or crypto-js work too: Zephyr TV has them built in, as Nuvio does.
- You can switch off an add-on or a plugin repository and switch it on again. When it is off, it keeps its settings, but its content does not show.
- Select a plugin repository to move it up or down. Its **Watch on** rows come in this order, as the add-ons' do.

## 1.5.2

- In **Search**, **Say it** comes before **Filters**.
- The search filter **Films or series** is now **Type of content**: **All**, **Films**, **Series** or **TV channels**.
- The search filters have **Adult Content**: **Show**, **Do not show** or **Only adult content**. It shows only when 18+ content is unlocked. When 18+ is locked, adult content never shows in the search.

## 1.5.1

- When you dictate a search, the words show in the search box before the search starts.
- The search results tile shows at once, with the words you search for, while the results load.
- When the TV goes to standby or turns off, 18+ content locks again.

## 1.5.0

- **Content catalog** can have more than one catalog. Select **Add a catalog** to add Cinemeta, Nuvio Catalog, a catalog added before, or a new address.
- The first catalog is the main catalog. It gives the details, the search and the pictures of the titles. When it does not have a title, Zephyr TV asks the other catalogs in their order.
- The other catalogs add their rows to **Films** and **Series**, in their order. Select a catalog to move it up or down, or to remove it.

## 1.4.0

- A film page shows **Watch on** for each add-on that has the film, with its icon and the number of streams. Before, only episodes had this.
- When you add an add-on with channels and with films or series, the message tells all of them.
- **Catalog source** is now **Content catalog**, in **Content**. Select the catalog there.
- **Complete with TMDB** on the **Content catalog** page shows titles of the catalog in the language of the TV. It is off with Nuvio Catalog, because Nuvio is in that language already.
- The TMDB key, the TMDB language, the services and the suggested apps are on the **Content catalog** page.
- **TMDB Spotlight** is now **Spotlight**. It has only the settings of the Spotlight banner.
- In the Glass theme, the text on title and channel pages shows without a pane around it, as the title does.
- New: **18+ lock**, in the users menu, in **Settings**. When it is on, adult add-ons do not show in the apps row, the search or the rows.
- Select **Unlock 18+** in the users menu and type the PIN to show adult add-ons for 2 hours. You can set it to stay unlocked until you select **Lock 18+**.
- An add-on that says it has adult content in its manifest always counts as 18+.
- New: **Adult add-ons**, in **Add-ons**. Set it to **In Adult Content** to show the channels, films and series of adult add-ons in an app of their own, **Adult Content**. They do not show in the other apps. With the 18+ lock, the app shows only when 18+ is unlocked.
- In **Add-ons**, each add-on shows its icon before its name. **Watch on** rows show the icon of the add-on too.

## 1.3.0

- Zephyr TV shows the catalogs of add-ons with types of their own, for example adult add-ons. It plays their titles as films.
- Zephyr TV waits longer for streams from slow add-ons, for example AIOStreams. It waits less when one add-on already has streams.
- When one catalog of an add-on fails, Zephyr TV keeps the other catalogs.
- **Catalog source** has **Nuvio Catalog (TMDB)**. It shows films and series in the language of the TV.
- On the first start, Zephyr TV asks you to choose Nuvio Catalog or Cinemeta.

## 1.2.10

- The text about a film or series on its page shows without a box around it.
- The app buttons on a title page show a ring when they have focus. They do not grow and get cut off.
- When a film or episode starts, the player shows the wide picture of the title with its logo, without a box.
- The player rounds the corners of the picture when it has no transparent parts.
- Zephyr TV does not open App info or the Zephyr TV switch in notification access directly. Some TVs showed that the app is not safe.

## 1.2.9

- **Show what's playing** opens the Zephyr TV switch in notification access, where the TV has it (Android 11 and newer).
- Zephyr TV skips the empty settings screens of some TVs and shows the ADB command at once.
- On a TV with little memory, **Show what's playing** says that it may not work there.
- On Android 13 and newer, the Home button setup has **Open App info**. Use it to allow restricted settings.
- **Default launcher** tries the TV's home app settings, then the Android home app chooser, then shows the ADB commands.
- The ADB commands include one that makes Zephyr TV the home app.
- **Cinemeta library** is now **Catalog source**. Cinemeta stays the default.
- Select **Add a catalog** to add another catalog add-on. Select it in the list and select **Use this catalog** to use it in place of Cinemeta.

## 1.2.8

- Focus stays on its card when a row under an app changes, for example when names in your language arrive. Before, focus on a lower row went back up to the apps.

## 1.2.7

- In the Glass theme, the title and channel pages show the glass pane behind their picture, as the settings pages do.
- In the Glass theme, the title and channel pages use glass panes under the picture, like the cards on the home screen. This includes the description and the buttons.
- Focus makes a glass button lighter. Its text stays white.

## 1.2.6

- You can navigate left and right between streaming apps in title details.
- Pressing down from the description moves focus to the first app.
- The player receives the exact cover poster shown in the details panel.
- The player displays a full-screen blurred background of the title artwork while loading.

## 1.2.5

- Trakt synchronization now requests full episode progress to correctly mark all watched episodes in series details.
- In the player, the channel and title logo now has rounded corners.
- A soft blurred version of the logo shows behind it as a background.

## 1.2.4

- The play icon changes color when a watch button is in focus.
- The title detail page starts with focus on the description.
- Series detail pages list seasons first.
- In season detail pages, press left or right to switch between seasons.
- Available streaming apps show as a horizontal row of icons in title details.

## 1.2.3

- Trakt synchronization now imports watched series even when individual episode details are not returned by Trakt.

## 1.2.2

- The **Films**, **Series** and **TV channels** icons are larger on their home screen tiles.

## 1.2.0

- Stremio add-ons have an **Adult content (18+)** setting. Content from these add-ons does not save viewing history or progress, and does not show in Continue Playing.
- Search filter options are collapsed by default under a **Filters** row.
- In the search panel, you can navigate down to focus the QR code.
- In the Series TV virtual app, the **Series watched** row now correctly lists your series watched from Trakt.
- The **Films**, **Series** and **TV channels** tiles use updated icons with transparency and support greyscale in monochrome mode.

## 1.1.0

- In **Updates**, Down now goes from **Receive betas** to the notes of the version.
- The users icon shows the first character of the name: a letter, or an emoji. To type a name with an emoji, select **Type it on your phone**.
- **Rename sections** is only for the Films, Series and TV channels tiles, and for the tiles of the add-ons.

## 1.0.0

- Piume is now **Zephyr TV**: a new name and a new home, github.com/mccoy88f/Zephyr_TV. Zephyr TV is an app of its own, so it installs next to Piume.
- When Zephyr TV starts for the first time, it takes from Piume your settings, your users, what you watched, your progress and your Trakt accounts.
- When Zephyr TV is your Home, it asks if you want to uninstall Piume.
- Zephyr TV can restore the backups of Piume.
- Zephyr TV has a new logo and a new banner on the home screen of the TV.
- The **Films**, **Series** and **TV channels** tiles have their own pictures.
- **Zephyr TV settings → System → About** shows the version, the credits and the license, with QR codes to the GitHub pages of mccoy88f, of the source code and of Plume Launcher.
- When you select a film or series, its page opens at once and shows **Loading** until the information is there.
- The page of a series tells you that you can hold OK on an episode to mark it.
- Search has **Clear the filters**. The results of a search with words also show the filters.
- In the list of years, Left and Right go back and forward 10 years.
- With Trakt, the first sync sends to Trakt what you watched before. After that, what you remove on Trakt is removed in Zephyr TV too. A title that you finish elsewhere leaves Continue Playing.
- An episode that you watch to the end in Zephyr TV counts as watched on Trakt too, also when it is short.
- Long downloads (TV guide, channel logos, updates, Trakt) do not slow down the pages and the search any more.
- To open **Settings** in the users panel, you must type the PIN when the layout lock is on. Changing the user in use does not need it.
- Zephyr TV does not disconnect Trakt by itself any more when the home screen and the player renew the access at the same time.
- When Trakt refuses the account, Zephyr TV tells you to connect it again. When Trakt refuses a sign-in, the TV tells you at once.
- On the page of a film, series or episode, the focus stays on its row when the page gets new information.
- What you watch is not lost any more when the player and the home screen save it at the same time.
- A series gets ✓ when you watched all its episodes. Specials do not count.

Before Zephyr TV, this app was called Piume. The versions of Piume follow.

## 4.2.0

- Films and series from Stremio add-ons. **Cinemeta**, the free library of Stremio, fills the new **Films** and **Series** tiles with rows. To give each add-on its own tile, select **Piume settings → Stremio add-ons → Films and series**.
- Cinemeta is always on. To use another Cinemeta address, select **Piume settings → Stremio add-ons → Cinemeta library**.
- With a TMDB key, the names, posters, genres and descriptions of films and series, and the names of episodes, are in your language.
- The cards show the year, the genre and the rating, with the icon of their tile. A ✓ shows the titles that you watched.
- Select a film to see its page, with **Watch**, the rating, the trailer and the services that have it. When one add-on alone has the streams, the page says **Watch on** and its name.
- On the page of a series, Left and Right change the season. Select an episode to see its page and its streams. When more than one add-on has streams, the page has **Watch on** for each add-on.
- Piume plays films and episodes in its own player. It tries the streams of all the add-ons, and the next one if a stream does not work. Only direct links play, not torrents.
- In the player, Left and Right go back and forward 10 seconds. CH+ and CH- go to the previous and next episode. When an episode ends, the next one starts.
- The player shows the season, the number and the name of the episode. The names of streams show on two lines. The sound goes to the audio output of the TV, also to external speakers.
- When you go back from the player, the page that you came from shows again.
- Films and episodes that you begin show in **Continue Playing**. When an episode ends, the next episode shows there with **Up next**.
- Piume remembers the films and episodes that you watch to the end. A watched film has **Watch again**. To change a mark, select **Mark as watched** or **Mark as not watched**. On a series, hold OK on an episode, or select **Mark season as watched**.
- The **Films** tile has a **Films watched** row at the bottom, and the **Series** tile has a **Series watched** row. The most recent is first.
- The page of a film or episode shows when you watched it. The page of a series shows the last episode that you watched.
- Piume has users. Select the users icon, left of search, and select a name to use that user. The icon shows the first two letters of the name. Each user has their own Continue Playing and ✓ marks.
- To add, rename or remove a user, or to connect Trakt, select **Settings** under the names.
- Each user can connect a Trakt account. Select **Connect Trakt** and scan the code with your phone. What you watch in Piume goes to Trakt. What you watched elsewhere gets ✓, and where you paused shows in Continue Playing.
- Search finds films and series in Cinemeta and in the add-ons. With a TMDB key, it also finds them by their names in your language. A result opens the same page as in the Films and Series tiles.
- Search has filters: **Films or series**, **Year** and **Genre**. Select **Search** with no words to find the titles for the filters.
- Spotlight works without a TMDB key: it shows the popular films and series of Cinemeta. With a TMDB key, you can still select a TMDB list in **Piume settings → Spotlight → Source**. Spotlight has a **Watch** button.
- To give a section your own name, hold OK on an app and select **Rename sections**.

## 4.1.0-beta.6

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- Piume has users. Select the users icon, left of search, to add a user or to change the user in use. Each user has their own Continue Playing and ✓ marks.
- Each user can connect their Trakt account. Select the user, then **Connect Trakt**, and scan the code with your phone.
- With Trakt, what you watch in Piume goes to your Trakt history. What you watched elsewhere gets ✓, and where you paused shows in Continue Playing.

## 4.1.0-beta.5

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- Piume remembers the films and episodes that you watch to the end. Their cards and pages show ✓.
- The page of a film that you watched has **Watch again**. To change the mark, select **Mark as watched** or **Mark as not watched**.
- On the page of a series, hold OK on an episode to mark it as watched or not watched. To mark all the episodes of the season, select **Mark season as watched**.
- When an episode ends, the next episode stays in **Continue Playing** with **Up next**. Continue Playing shows one card for each series.
- The page of a series opens on the season and the episode to watch next.
- Cinemeta is the source of all films and series: the Films and Series tiles, the search and Spotlight. TMDB only translates them and adds the trailer and the services.
- Search finds films and series in Cinemeta and in the add-ons. With a TMDB key, it also finds them by their names in your language.
- Select a search result to see the same page as in the Films and Series tiles, with its seasons, episodes and streams.
- Spotlight has a **Watch** button when an add-on has streams for films or series. It opens the page of the title.

## 4.1.0-beta.4

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- On the page of a series, Left and Right go to the previous and next season.
- Select an episode to see its page: its picture, season, number, name, date and description. Piume then asks the add-ons for its streams.
- When more than one add-on has streams for the episode, the page has **Watch on** for each add-on, with the number of streams.
- The player shows the season, the number and the name of the episode.
- When you go back from the player, the page of the film, series or episode shows again, with its progress.
- Spotlight works without a TMDB key. It shows the popular films and series of Cinemeta. With a TMDB key, their names, descriptions and genres are in your language.
- With a TMDB key, you can still select one of the TMDB lists in **Piume settings → Spotlight → Source**.

## 4.1.0-beta.3

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- The cards of films and series show the year, the genre and the rating, with the icon of their tile.
- The page of a film or series shows the rating. With a TMDB key, it also has the trailer and the services that have the title.
- The page of a film or series and the player show the same picture as the card.
- To give a section your own name, hold OK on an app and select **Rename sections**. To get the name back, select **Original name**.
- Cinemeta is always on. To use another Cinemeta address, select **Piume settings → Stremio add-ons → Cinemeta library**.
- On the page of a search result, **Watch on** plays a film immediately. For a series, it shows the episodes.
- In the player, the names of streams show on two lines.
- The player sends the sound to the audio output of the TV, also to external speakers. It pauses when headphones or a Bluetooth speaker disconnect.

## 4.1.0-beta.2

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- When only one add-on has streams for a film or series, its page says **Watch on** and the add-on's name (or **Resume from 12:34 on** the add-on), with the add-on's icon. The same is true on the page of a TMDB result.
- Films and series from Stremio add-ons. **Cinemeta**, the catalog of Stremio, fills the new **Films** and **Series** tiles with rows. To give each add-on its own tile, select **Piume settings → Stremio add-ons → Films and series**. You can turn Cinemeta off in the same section.
- Select a film or a series to see its page. A film has **Watch**. A series has its seasons and episodes.
- Piume asks all the add-ons that have streams for the title. It plays the first stream that works, and tries the next one if it does not work. To choose another stream, use the **Stream** button in the player.
- Only direct links play (http addresses, also .php addresses). Torrents do not play.
- In the player, Left and Right go back and forward 10 seconds (more if you hold them). CH+ and CH- go to the previous and next episode. When an episode ends, the next one starts.
- The films and episodes that you begin show in **Continue Playing** with their progress. They start again where you stopped.
- Without a TMDB key, search finds films and series in the add-ons' catalogs. With a TMDB key, the page of a film or series has **Watch with the add-ons**.
- You can add an add-on that has only films and series, with no TV channels.
- Cinemeta is in English only. With a TMDB key, the names, posters, genres and descriptions of films and series, and the names and descriptions of episodes, come from TMDB in the language of Piume. What TMDB does not have in your language stays in English.

## 4.1.0-beta.1

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- Films and series from Stremio add-ons. **Cinemeta**, the catalog of Stremio, fills the new **Films** and **Series** tiles with rows. To give each add-on its own tile, select **Piume settings → Stremio add-ons → Films and series**. You can turn Cinemeta off in the same section.
- Select a film or a series to see its page. A film has **Watch**. A series has its seasons and episodes.
- Piume asks all the add-ons that have streams for the title. It plays the first stream that works, and tries the next one if it does not work. To choose another stream, use the **Stream** button in the player.
- Only direct links play (http addresses, also .php addresses). Torrents do not play.
- In the player, Left and Right go back and forward 10 seconds (more if you hold them). CH+ and CH- go to the previous and next episode. When an episode ends, the next one starts.
- The films and episodes that you begin show in **Continue Playing** with their progress. They start again where you stopped.
- Without a TMDB key, search finds films and series in the add-ons' catalogs. With a TMDB key, the page of a film or series has **Watch with the add-ons**.
- You can add an add-on that has only films and series, with no TV channels.
- Cinemeta is in English only. With a TMDB key, the names, posters, genres and descriptions of films and series, and the names and descriptions of episodes, come from TMDB in the language of Piume. What TMDB does not have in your language stays in English.

## 4.0.2

- In the player, OK puts a frame on the program on now. Press OK again to see the full description. Press Down to go to the buttons, and Up to go back to the program.
- The player buttons are now icons. They are on the right, next to the stream line and the audio line.
- In search, the page of an add-on channel shows the app it plays from, with its icon. For example, **Watch on OMG+ TV - DDT**.
- In **Continue Playing**, the card of the add-on channel you watched last has a small icon, as the other cards do. It is the icon of the app it plays from: the add-on's app, the IPTV app or the TV app.

## 4.0.1

- The settings section **TV & IPTV** is now called **TV & EPG**.

## 4.0.0

- Piume can show the TV channels of Stremio add-ons, for example OMG+ TV. Piume settings have a new section, **Stremio add-ons**.
- Select **Add an add-on** and scan the QR code. Paste the address of the add-on's manifest on your phone. To type it with the remote, select **Type the address with the remote**.
- The channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app. An app per add-on has the add-on's name and logo.
- Select an add-on and then **Move up** or **Move down** to change the order of its rows.
- The cards show the program on now with a progress bar, as the add-on gives it. Logos come from the add-on first, then from iptv-org.
- Select a channel to play it in the new Piume player. CH+ and CH- change the channel. You can also type the channel number, or press the Last channel key.
- When a channel loads, the player shows its logo and name. If a stream does not work, the player tries the next stream automatically.
- Press OK to show the banner with the program on now, the stream and its resolution. Press OK again to see the full description and what is next.
- When the banner shows, press Down, Left or Right to go to its buttons: **Pause**, **Audio**, **Subtitles** and **Stream**.
- When the banner does not show, Left and Right change the stream. Hold OK (or press Menu) to see the audio tracks and the subtitles. Piume remembers the languages you choose.
- The channel you watched last shows in **Continue Playing**. Select the tile of an add-on (or the IPTV app) to watch it again.
- Search finds the channels of the add-ons too. Search works also without a TMDB key.
- Hold OK on a channel of an add-on and select **Details** to see what the add-on tells about it.
- The row **Continue Watching** is now called **Continue Playing**, because it also shows music and other things that play.
- On the home screen, press Back on a card in a lower section to go back to the first section of the app.
- The lists of rows and sections in Piume settings have **Select all** and **Deselect all**.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 4.0.0-beta.9

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- In the player, press OK to show the banner. Then Up and Down go to the buttons **Pause**, **Audio**, **Subtitles** and **Stream**. They do not change the channel. When the banner is not shown, Up and Down change the channel as before.
- The player banner shows the resolution of the stream next to its name, for example **1080p** or **4K**.
- The player banner has a **Pause** button, first before **Audio**. Press it to pause the channel, and again (**Play**) to play it. While the channel is paused, the banner stays and shows **Paused**. A live channel that is paused for a long time goes back to the live broadcast when it plays again.
- When a channel loads, the player shows its logo and name in the middle of the screen. Below them it shows **Loading stream 1 of 3**, when the channel has more than one stream. If a stream does not work, the player tries the next one automatically. If no stream works, it shows **No stream available**.
- The player banner has the buttons **Audio**, **Subtitles** and **Stream**. While the banner shows, press Left or Right to go to them, then OK to open the list and choose. When the banner is not shown, Left and Right change the stream as before.
- Hold OK (or press Menu) to see the audio tracks and the subtitles together. The banner shows which ones play. Piume remembers the languages you choose for the next channels. The audio and subtitles keys of the remote also work.
- The player shows subtitles, also the picture subtitles that many IPTV streams have.
- In the player, the banner shows only the program on now and the stream. Press OK again to see the full description and what is next.
- In the player, Right no longer closes the player when the other stream does not work. The player tries the next stream, also the ones before it.
- In **Stremio add-ons**, select an add-on and then **Move up** or **Move down** to change the order. In the TV app and the IPTV app, the rows of the first add-on show first.
- The rows of a large IPTV add-on no longer stop the other apps. Before, while Piume loaded them, no app showed its rows.
- Piume keeps the rows of an add-on. When you go back to its tile, they show at once. Before, Piume loaded them again each time.
- Rows far down get their cards when you come near them. With many categories, the home screen stays smooth.
- Piume reads each add-on with fewer requests and keeps less on the TV.
- Logos and what is on come from the add-on first. Piume uses the logos from iptv-org and your TV guide only when the add-on has none, or when its logo does not load.
- In the player, CH+ and CH- (or Up and Down) go to the next and previous channel of the row. You can also type the channel number, or press the Last channel key.
- In the player, Left and Right change to another stream of the same channel. A stream that stops or does not start goes to the next stream automatically.
- In the player, OK or Info shows the channel's logo, its number in the row, the time, the program on now with a progress bar, what is next and the stream.
- The channel you watched last shows in **Continue Playing**. Select the tile of an add-on (or the IPTV app) to watch it again.
- Search finds the channels of the add-ons too, in the same row as the TV channels. It works also without a TMDB key.
- Hold OK on a channel of an add-on and select **Details** to see what the add-on tells about it.
- The row **Continue Watching** is now called **Continue Playing**, because it also shows music and other things that play.
- Piume settings have a new section, **Stremio add-ons**. Select **Add an add-on** and type the address of the add-on's manifest. You can also paste it on your phone.
- The TV channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app.
- An app per add-on has the add-on's name and logo, from its manifest.
- Select a channel to play it in the Piume player. If a stream does not play, the player tries the next stream.
- The cards show the program on now, if the add-on gives it.
- Piume reads the channels of each add-on again every 15 minutes.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 4.0.0-beta.8

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- The player banner has a **Pause** button, first before **Audio**. Press it to pause the channel, and again (**Play**) to play it. While the channel is paused, the banner stays and shows **Paused**. A live channel that is paused for a long time goes back to the live broadcast when it plays again.
- When a channel loads, the player shows its logo and name in the middle of the screen. Below them it shows **Loading stream 1 of 3**, when the channel has more than one stream. If a stream does not work, the player tries the next one automatically. If no stream works, it shows **No stream available**.
- The player banner has the buttons **Audio**, **Subtitles** and **Stream**. While the banner shows, press Left or Right to go to them, then OK to open the list and choose. When the banner is not shown, Left and Right change the stream as before.
- Hold OK (or press Menu) to see the audio tracks and the subtitles together. The banner shows which ones play. Piume remembers the languages you choose for the next channels. The audio and subtitles keys of the remote also work.
- The player shows subtitles, also the picture subtitles that many IPTV streams have.
- In the player, the banner shows only the program on now and the stream. Press OK again to see the full description and what is next.
- In the player, Right no longer closes the player when the other stream does not work. The player tries the next stream, also the ones before it.
- In **Stremio add-ons**, select an add-on and then **Move up** or **Move down** to change the order. In the TV app and the IPTV app, the rows of the first add-on show first.
- The rows of a large IPTV add-on no longer stop the other apps. Before, while Piume loaded them, no app showed its rows.
- Piume keeps the rows of an add-on. When you go back to its tile, they show at once. Before, Piume loaded them again each time.
- Rows far down get their cards when you come near them. With many categories, the home screen stays smooth.
- Piume reads each add-on with fewer requests and keeps less on the TV.
- Logos and what is on come from the add-on first. Piume uses the logos from iptv-org and your TV guide only when the add-on has none, or when its logo does not load.
- In the player, CH+ and CH- (or Up and Down) go to the next and previous channel of the row. You can also type the channel number, or press the Last channel key.
- In the player, Left and Right change to another stream of the same channel. A stream that stops or does not start goes to the next stream automatically.
- In the player, OK or Info shows the channel's logo, its number in the row, the time, the program on now with a progress bar, what is next and the stream.
- The channel you watched last shows in **Continue Playing**. Select the tile of an add-on (or the IPTV app) to watch it again.
- Search finds the channels of the add-ons too, in the same row as the TV channels. It works also without a TMDB key.
- Hold OK on a channel of an add-on and select **Details** to see what the add-on tells about it.
- The row **Continue Watching** is now called **Continue Playing**, because it also shows music and other things that play.
- Piume settings have a new section, **Stremio add-ons**. Select **Add an add-on** and type the address of the add-on's manifest. You can also paste it on your phone.
- The TV channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app.
- An app per add-on has the add-on's name and logo, from its manifest.
- Select a channel to play it in the Piume player. If a stream does not play, the player tries the next stream.
- The cards show the program on now, if the add-on gives it.
- Piume reads the channels of each add-on again every 15 minutes.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 4.0.0-beta.7

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- When a channel loads, the player shows its logo and name in the middle of the screen. Below them it shows **Loading stream 1 of 3**, when the channel has more than one stream. If a stream does not work, the player tries the next one automatically. If no stream works, it shows **No stream available**.
- The player banner has the buttons **Audio**, **Subtitles** and **Stream**. While the banner shows, press Left or Right to go to them, then OK to open the list and choose. When the banner is not shown, Left and Right change the stream as before.
- Hold OK (or press Menu) to see the audio tracks and the subtitles together. The banner shows which ones play. Piume remembers the languages you choose for the next channels. The audio and subtitles keys of the remote also work.
- The player shows subtitles, also the picture subtitles that many IPTV streams have.
- In the player, the banner shows only the program on now and the stream. Press OK again to see the full description and what is next.
- In the player, Right no longer closes the player when the other stream does not work. The player tries the next stream, also the ones before it.
- In **Stremio add-ons**, select an add-on and then **Move up** or **Move down** to change the order. In the TV app and the IPTV app, the rows of the first add-on show first.
- The rows of a large IPTV add-on no longer stop the other apps. Before, while Piume loaded them, no app showed its rows.
- Piume keeps the rows of an add-on. When you go back to its tile, they show at once. Before, Piume loaded them again each time.
- Rows far down get their cards when you come near them. With many categories, the home screen stays smooth.
- Piume reads each add-on with fewer requests and keeps less on the TV.
- Logos and what is on come from the add-on first. Piume uses the logos from iptv-org and your TV guide only when the add-on has none, or when its logo does not load.
- In the player, CH+ and CH- (or Up and Down) go to the next and previous channel of the row. You can also type the channel number, or press the Last channel key.
- In the player, Left and Right change to another stream of the same channel. A stream that stops or does not start goes to the next stream automatically.
- In the player, OK or Info shows the channel's logo, its number in the row, the time, the program on now with a progress bar, what is next and the stream.
- The channel you watched last shows in **Continue Playing**. Select the tile of an add-on (or the IPTV app) to watch it again.
- Search finds the channels of the add-ons too, in the same row as the TV channels. It works also without a TMDB key.
- Hold OK on a channel of an add-on and select **Details** to see what the add-on tells about it.
- The row **Continue Watching** is now called **Continue Playing**, because it also shows music and other things that play.
- Piume settings have a new section, **Stremio add-ons**. Select **Add an add-on** and type the address of the add-on's manifest. You can also paste it on your phone.
- The TV channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app.
- An app per add-on has the add-on's name and logo, from its manifest.
- Select a channel to play it in the Piume player. If a stream does not play, the player tries the next stream.
- The cards show the program on now, if the add-on gives it.
- Piume reads the channels of each add-on again every 15 minutes.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 4.0.0-beta.6

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- The player banner has the buttons **Audio**, **Subtitles** and **Stream**. While the banner shows, press Left or Right to go to them, then OK to open the list and choose. When the banner is not shown, Left and Right change the stream as before.
- Hold OK (or press Menu) to see the audio tracks and the subtitles together. The banner shows which ones play. Piume remembers the languages you choose for the next channels. The audio and subtitles keys of the remote also work.
- The player shows subtitles, also the picture subtitles that many IPTV streams have.
- In the player, the banner shows only the program on now and the stream. Press OK again to see the full description and what is next.
- In the player, Right no longer closes the player when the other stream does not work. The player tries the next stream, also the ones before it.
- In **Stremio add-ons**, select an add-on and then **Move up** or **Move down** to change the order. In the TV app and the IPTV app, the rows of the first add-on show first.
- The rows of a large IPTV add-on no longer stop the other apps. Before, while Piume loaded them, no app showed its rows.
- Piume keeps the rows of an add-on. When you go back to its tile, they show at once. Before, Piume loaded them again each time.
- Rows far down get their cards when you come near them. With many categories, the home screen stays smooth.
- Piume reads each add-on with fewer requests and keeps less on the TV.
- Logos and what is on come from the add-on first. Piume uses the logos from iptv-org and your TV guide only when the add-on has none, or when its logo does not load.
- In the player, CH+ and CH- (or Up and Down) go to the next and previous channel of the row. You can also type the channel number, or press the Last channel key.
- In the player, Left and Right change to another stream of the same channel. A stream that stops or does not start goes to the next stream automatically.
- In the player, OK or Info shows the channel's logo, its number in the row, the time, the program on now with a progress bar, what is next and the stream.
- The channel you watched last shows in **Continue Playing**. Select the tile of an add-on (or the IPTV app) to watch it again.
- Search finds the channels of the add-ons too, in the same row as the TV channels. It works also without a TMDB key.
- Hold OK on a channel of an add-on and select **Details** to see what the add-on tells about it.
- The row **Continue Watching** is now called **Continue Playing**, because it also shows music and other things that play.
- Piume settings have a new section, **Stremio add-ons**. Select **Add an add-on** and type the address of the add-on's manifest. You can also paste it on your phone.
- The TV channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app.
- An app per add-on has the add-on's name and logo, from its manifest.
- Select a channel to play it in the Piume player. If a stream does not play, the player tries the next stream.
- The cards show the program on now, if the add-on gives it.
- Piume reads the channels of each add-on again every 15 minutes.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 4.0.0-beta.5

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- In the player, the banner shows only the program on now and the stream. Press OK again to see the full description and what is next.
- In the player, Right no longer closes the player when the other stream does not work. The player tries the next stream, also the ones before it.
- In **Stremio add-ons**, select an add-on and then **Move up** or **Move down** to change the order. In the TV app and the IPTV app, the rows of the first add-on show first.
- The rows of a large IPTV add-on no longer stop the other apps. Before, while Piume loaded them, no app showed its rows.
- Piume keeps the rows of an add-on. When you go back to its tile, they show at once. Before, Piume loaded them again each time.
- Rows far down get their cards when you come near them. With many categories, the home screen stays smooth.
- Piume reads each add-on with fewer requests and keeps less on the TV.
- Logos and what is on come from the add-on first. Piume uses the logos from iptv-org and your TV guide only when the add-on has none, or when its logo does not load.
- In the player, CH+ and CH- (or Up and Down) go to the next and previous channel of the row. You can also type the channel number, or press the Last channel key.
- In the player, Left and Right change to another stream of the same channel. A stream that stops or does not start goes to the next stream automatically.
- In the player, OK or Info shows the channel's logo, its number in the row, the time, the program on now with a progress bar, what is next and the stream.
- The channel you watched last shows in **Continue Playing**. Select the tile of an add-on (or the IPTV app) to watch it again.
- Search finds the channels of the add-ons too, in the same row as the TV channels. It works also without a TMDB key.
- Hold OK on a channel of an add-on and select **Details** to see what the add-on tells about it.
- The row **Continue Watching** is now called **Continue Playing**, because it also shows music and other things that play.
- Piume settings have a new section, **Stremio add-ons**. Select **Add an add-on** and type the address of the add-on's manifest. You can also paste it on your phone.
- The TV channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app.
- An app per add-on has the add-on's name and logo, from its manifest.
- Select a channel to play it in the Piume player. If a stream does not play, the player tries the next stream.
- The cards show the program on now, if the add-on gives it.
- Piume reads the channels of each add-on again every 15 minutes.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 4.0.0-beta.4

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- The rows of a large IPTV add-on no longer stop the other apps. Before, while Piume loaded them, no app showed its rows.
- Piume keeps the rows of an add-on. When you go back to its tile, they show at once. Before, Piume loaded them again each time.
- Rows far down get their cards when you come near them. With many categories, the home screen stays smooth.
- Piume reads each add-on with fewer requests and keeps less on the TV.
- Logos and what is on come from the add-on first. Piume uses the logos from iptv-org and your TV guide only when the add-on has none, or when its logo does not load.
- In the player, CH+ and CH- (or Up and Down) go to the next and previous channel of the row. You can also type the channel number, or press the Last channel key.
- In the player, Left and Right change to another stream of the same channel. A stream that stops or does not start goes to the next stream automatically.
- In the player, OK or Info shows the channel's logo, its number in the row, the time, the program on now with a progress bar, what is next and the stream.
- The channel you watched last shows in **Continue Playing**. Select the tile of an add-on (or the IPTV app) to watch it again.
- Search finds the channels of the add-ons too, in the same row as the TV channels. It works also without a TMDB key.
- Hold OK on a channel of an add-on and select **Details** to see what the add-on tells about it.
- The row **Continue Watching** is now called **Continue Playing**, because it also shows music and other things that play.
- Piume settings have a new section, **Stremio add-ons**. Select **Add an add-on** and type the address of the add-on's manifest. You can also paste it on your phone.
- The TV channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app.
- An app per add-on has the add-on's name and logo, from its manifest.
- Select a channel to play it in the Piume player. If a stream does not play, the player tries the next stream.
- The cards show the program on now, if the add-on gives it.
- Piume reads the channels of each add-on again every 15 minutes.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 4.0.0-beta.3

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- Logos and what is on come from the add-on first. Piume uses the logos from iptv-org and your TV guide only when the add-on has none, or when its logo does not load.
- In the player, CH+ and CH- (or Up and Down) go to the next and previous channel of the row. You can also type the channel number, or press the Last channel key.
- In the player, Left and Right change to another stream of the same channel. A stream that stops or does not start goes to the next stream automatically.
- In the player, OK or Info shows the channel's logo, its number in the row, the time, the program on now with a progress bar, what is next and the stream.
- The channel you watched last shows in **Continue Playing**. Select the tile of an add-on (or the IPTV app) to watch it again.
- Search finds the channels of the add-ons too, in the same row as the TV channels. It works also without a TMDB key.
- Hold OK on a channel of an add-on and select **Details** to see what the add-on tells about it.
- The row **Continue Watching** is now called **Continue Playing**, because it also shows music and other things that play.
- Piume settings have a new section, **Stremio add-ons**. Select **Add an add-on** and type the address of the add-on's manifest. You can also paste it on your phone.
- The TV channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app.
- An app per add-on has the add-on's name and logo, from its manifest.
- Select a channel to play it in the Piume player. If a stream does not play, the player tries the next stream.
- The cards show the program on now, if the add-on gives it.
- Piume reads the channels of each add-on again every 15 minutes.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 4.0.0-beta.2

- This version is a beta. To get betas as updates, turn on **Piume settings → Updates → Receive betas**.
- Piume settings have a new section, **Stremio add-ons**. Select **Add an add-on** and type the address of the add-on's manifest. You can also paste it on your phone.
- The TV channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app.
- An app per add-on has the add-on's name and logo, from its manifest.
- Select a channel to play it in the Piume player. If a stream does not play, the player tries the next stream.
- The cards show the program on now, if the add-on gives it.
- Piume reads the channels of each add-on again every 15 minutes.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 4.0.0-beta.1

- This version is a beta. Piume does not offer it as an update. To try it, install ZephyrTV.apk by hand.
- Piume settings have a new section, **Stremio add-ons**. Select **Add an add-on** and type the address of the add-on's manifest. You can also paste it on your phone.
- The TV channels of an add-on show as rows. The first row has all the channels. Then each category has a row of its own.
- Select **TV channels** to choose where the rows show: **In the TV app**, **In an IPTV app** or **An app per add-on**. Without a TV app, they show in the IPTV app.
- An app per add-on has the add-on's name and logo, from its manifest.
- Select a channel to play it in the Piume player. If a stream does not play, the player tries the next stream.
- The cards show the program on now, if the add-on gives it.
- Piume reads the channels of each add-on again every 15 minutes.
- The backup includes the add-ons.
- Piume is larger: about 1.2 MB. The player needs the extra space.

## 3.1.1

- **Piume settings → Updates** has a new option, **Receive betas**. Turn it on to get the beta versions too. A beta has new functions that are not fully tested yet.
- Piume now knows that a beta comes before its final version. After a beta, Piume offers the final version when it is available.

## 3.1

- The settings section **Continue Watching** is now called **Content**.
- Hold OK on a card: **Remove from this section** replaces **Remove from Continue Watching**, because it works in every section. After it, the same section shows again. Before, the home screen went back to Continue Watching.
- Hold OK on a TV channel and select **Details** to see its page: the logo, the program on now with its time and description, what is next, and **Watch on TV**. It is the same page that search shows.
- In **All apps**, an app in the first or last column shows all of its tile when it has the focus. Before, its edge was cut.
- Piume settings have a new section, **TV & IPTV**. It has the TV inputs, the channel logos and the TV guide. Before, they were in **Favourites** and **Continue Watching**.
- What is playing now shows in the **Continue Watching** row, before the shows. It is no longer a row of its own.
- Hold OK on a playing item and select **Close** to stop it and remove it from the row. It comes back when it plays again.
- A track that stays paused for more than 6 hours goes from the row automatically.
- On Android 8.0, Piume no longer stops when it checks if it can see what is playing.

## 3.0.6

- **Playing now** changes when the track changes, also with apps like Spotify that do not tell other apps about it.

## 3.0.5

- The first row of **Play queue** is called **Playing now** again. Before, it was sometimes called **Continue Watching**.

## 3.0.4

- **Playing now** needs notification access, and only the system can give it. Select **Piume settings → Continue Watching → Show what's playing** to see if Piume has it. If not, the page shows the adb command to run once. The Home button setup shows it too.
- Pictures that are not wide, like the square Spotify covers, show whole on a blur of themselves and fill the card.
- In Piume settings, the TV inputs options say **Favourites** like the rest.

## 3.0.3

- The TV inputs button in the top bar shows a green dot when at least one input has an active device.

## 3.0.2

- In the TV inputs menu, only inputs with an active device or signal show the green dot.
- Fix selected row icon visibility in the TV inputs menu.
- New option to show connected TV inputs in your Favourites even in menu mode (**Piume settings → Favourites → Show connected inputs in Favourites**).

## 3.0.1

- Choose how to show TV inputs: as a menu in the top bar or as tiles in your Favourites (**Piume settings → Favourites → TV inputs**).
- TV inputs show dedicated brand marks for PlayStation, Xbox, Nintendo Switch, Apple TV, set-top boxes, and media players.
- The TV inputs popup menu now matches the current theme and has smoked glass styling in the Glass theme.
- Fixed the updates page staying stuck until reopened when a new version is found.
- The TV guide cache now stays for up to 24 hours between refreshes.

## 3.0.0

- The first tile is now **Play queue**. Its first row, **Playing now**, shows what plays now in any app, like Spotify or YouTube, with the art and the progress. Select a card to open that app. **Continue Watching** comes under it.
- The apps row is now called **Favourites**, in the home screen and in Piume settings.
- Piume is now in Spanish and in French.
- The network icon shows a red dot when there is no internet.
- The Piume settings icon shows a red dot only when an update is ready. The dot also shows on **System** and on **Updates**, so it leads you to the update.
- Some Italian texts are corrected.

## 2.9.4

- The green dots of the TV inputs show only the inputs with a device connected. Piume reads the state from Android. On Sony TVs, Android marks all HDMI inputs as connected, so Piume reads the state from the Sony inputs app instead. On other TVs, the dots are as correct as the state that the TV gives to Android.

## 2.9.3

- In the TV inputs menu, a green dot shows next to each input with a device that is connected and on.
- On the TV channel cards, a long program title scrolls when the card has the focus.

## 2.9.2

- The TV inputs button in the top bar shows a green dot when a device on HDMI is connected and on.

## 2.9.1

- The TV channels show at once, with their names and numbers. The logos come one by one as they load, and the program that is on now comes last.

## 2.9

- Spotlight can show more TMDB lists: trending this week, popular, top rated, films now in cinemas, films coming soon and series on the air. Select **Piume settings → TMDB Spotlight → Spotlight shows**.
- **My TMDB list** is selected only after you set a list. If you do not set one, Spotlight keeps what it showed before.

## 2.8.2

- Search results show at once. Their covers with the title come a moment later, in their place.
- The rows show 10 cards at a time. When you move near the end of a row, the next 10 cards come.
- The TV app shows all the channels, not only the first 30.
- Piume keeps the channel logos on the TV. It downloads them again only each month, or when you select **Update the channel logos**.

## 2.8.1

- Search results show in three rows: films, series, then TV channels. Each card shows the year and the genre.

## 2.8

- Search also finds the TV channels, by name or by number. They show in a row of their own, after the films and series.
- Press OK on a channel to see its page: the logo, the program that is on now with its time and description, and the next program. Select **Watch on TV** to open the channel.

## 2.7.2

- Piume also finds channels in a TV guide by their guide ID, like "Rai1.it" or "Canale.5.it".

## 2.7.1

- Piume finds more channels in TV guides that put the country before the name, like "IT - Canale 5".
- When a TV guide has no programs for today, Piume tells you that the guide is not up to date.

## 2.7

- The TV channel cards can show the program that is on now from a TV guide on the internet. Many TVs do not let apps read their own guide.
- To set the guide, select **Piume settings → Continue Watching → TV guide (XMLTV)**. Type the address of a guide in XMLTV format, or paste it on your phone with the QR code. Piume downloads the guide two times each day.

## 2.6.3

- More channel logos load, for example Rete 4 and Italia 1. Piume now says who it is when it downloads pictures.

## 2.6.2

- Small changes to help find problems with the TV guide.

## 2.6.1

- The program that is on now shows on the TV channel cards. In 2.6 the TV did not give it to Piume.

## 2.6

- The TV channel cards show the program that is on now, after the channel number. The bar under the card shows how far into the program you are. The guide comes from the TV.
- Channels that the TV lists two times show one time only.
- More channels get their logo. Piume finds names like "TELEAMBIENTE" for "TeleAmbiente TV" and "Mediaset Italia2" for "Italia 2". It also uses the logos that iptv-org has only as SVG on Wikimedia.
- When you go back from an app, the home screen shows where you left it. The Home key still goes back to the start of the apps row.

## 2.5.1

- The blurred background of the channel cards and of the search results is smoother.

## 2.5

- The TV channel cards show the logo of the channel, large in the middle on a blur of itself. The name and the number of the channel are under the card.
- When the TV has no logo for a channel, Piume gets it from iptv-org, a public list of TV channels. Piume downloads the list for your country the first time and then each month.
- To turn this off, select **Piume settings → Continue Watching → Channel logos (iptv-org)**. To get the latest logos now, select **Update the channel logos**.

## 2.4.13

- Search results without a picture on TMDB now show the poster or the name of the title. Before, their card was empty.

## 2.4.12

- Every search result fills its card with a picture. When TMDB has no wide picture, the card shows the poster on a blur of itself. When TMDB has no picture at all, the card shows the logo or the name of the title on a colour.
- The arrows of the rows are level with the middle of the tiles and the cards.

## 2.4.11

- The arrows at the sides of the rows and of Spotlight are smaller.
- When you go down from the apps row to the recommendations, the arrows of the apps row go away.
- The Piume settings pages show only the arrow at the bottom, while there are more items below. The arrow at the top is gone.

## 2.4.10

- In Piume settings, each section shows an arrow instead of a value. The arrow tells you that the section opens a page.

## 2.4.9

- Arrows show at the sides of the apps row and of the recommendation rows when there are more items that way. They show only on the row that has the focus.
- The Spotlight arrows show only when Spotlight has the focus.
- An arrow shows at the bottom of the Piume settings pages when the page has more items below.
- In the Glass theme, the edges of the cards and of the Spotlight buttons are thinner and softer.
- In the Glass theme, the side page (Piume settings, search, the page of a result) is smoked glass over the home screen.
- On the page of a search result, the first item stays in full view when you go back up to it.

## 2.4.8

- On the page of a search result, the description shows again when you go back up to the first item.
- The description lines up with the title of the page.

## 2.4.7

- The cover on the page of a search result now reaches the edges of the page and the top of the screen.

## 2.4.6

- On the page of a search result, the cover is at the very top, above the title. It fills the full width of the page.

## 2.4.5

- The page of a search result shows its wide cover at the top, with the text under it.
- Each app on that page shows its icon next to its name.

## 2.4.4

- Search results are wide covers with the title on them, in the language that you set for TMDB, or else in English. When TMDB has no such cover, the card shows the logo of the title on the picture.
- Pictures in the recommendations, like the YouTube ones, fill the card. They have no black bars.

## 2.4.3

- **YouTube trailer** is the first button in Spotlight and the first item on the page of a search result.

## 2.4.2

- When the Spotlight title has the focus, a soft band goes across the full screen behind it. It has no border.
- The Spotlight description uses all the width of the screen.

## 2.4.1

- The keyboard closes when you search, and when you close a page of Piume settings.

## 2.4

- Search results no longer replace Spotlight. A **Results** tile shows after **Continue Watching**, with the results as cards under the apps row.
- Press OK on a result to see its description, the YouTube trailer and the apps that have it.
- Press Back, or hold OK on the **Results** tile, to close the search.
- **Say it** listens in the search page. The words show in the search box while you speak. The first time, allow Piume to use the microphone.
- In the search page, Down goes from the search box to **Say it**.
- The Spotlight title shows a soft panel when it has the focus.
- The pages that open on your phone from a QR code show the name of the TV at the bottom.

## 2.3

- The top bar has a **Search** button with a magnifying glass. Type a title with the remote, say it, or type it on your phone with the QR code.
- Search finds films and series on TMDB. Spotlight shows the results, with the description, the trailer and the apps that have the title. Press Back to close the search.
- Hold OK on **Search** to open the voice assistant of the TV.
- When you move along the apps row, empty cards show while the recommendations of the app load. The cards of the previous app do not stay on the screen.
- From the apps row, Up goes to the Spotlight buttons first, then to the title, then to the top bar.

## 2.2

- The new **Glass** theme shows the wallpaper through glossy glass cards. The text is white and easy to read on any photo. Select **Piume settings → Appearance & wallpapers → Theme → Glass**. It uses the dark wallpaper.

## 2.1

- In Piume settings, a long value no longer cuts the name of the setting. The value is shorter instead.
- Each row that an app shows on the home screen is now a section under the apps row. For example, YouTube shows **Recommended** and **Trending** as two rows. Move down to see the next section.
- To select the sections of an app, hold OK on the app and select **Visible sections**.
- The TV app shows all the channels of the TV, from channel 1, in number order. Each tuner is a section. All channel cards have the same design on all TVs.
- On a TV without a tuner, the TV app shows no channels.
- Spotlight does not change the title while the title or its buttons have the focus.
- Press OK on the Spotlight title to show all of a long description. Press OK again to make it short.
- The Spotlight title is a little higher. The space above it and the space below it are the same.
- The first and the last item of a row now show all of their shadow when they have the focus.

## 2.0

- The Spotlight image is clearer. It has a fade only under the apps row. The Spotlight text has no panel behind it. It has the same soft shadow as the clock.
- Spotlight can show your own list from themoviedb.org instead of the trending titles. Make a public list there, then select **Piume settings → TMDB Spotlight → My TMDB list** and paste its link on your phone.
- The Gallery screensaver can show the Spotlight trending images, with the title, the year and the genre. Select **Piume settings → Screensaver → Screensaver → Spotlight trending (TMDB)**.
- The top bar has a voice search button. It opens the voice search of the TV.
- You can show the clock in 24-hour or 12-hour format. Select **Piume settings → Appearance & wallpapers → Clock**.
- **Night mode** makes the home screen darker and stops Spotlight between the hours you choose. Turn it on in **Piume settings → Appearance & wallpapers → Night mode**.
- You can save a backup of your Piume settings on your phone and restore it later. Select **Piume settings → System → Backup and restore**. The backup has your TMDB key, but not your photos or the lock PIN.

## 1.5

- Spotlight keeps its images on the TV. It downloads each image one time, not each time it shows the title.
- Spotlight gets the next image before it shows it. The next title appears without a wait.
- Spotlight shows the trending titles in a random order.
- Spotlight stops after 15 minutes without a key press. Press a key to start it again.
- Security: other apps cannot make Piume start their apps through the update screen.
- Security: a card in Continue Watching can only open the app that added it.
- Security: Android no longer copies Piume settings and your TMDB key to backups.
- You can enter your TMDB API key from your phone. Select **Piume settings → TMDB Spotlight → TMDB API Key → Enter from your phone**, then scan the code. Piume checks the key with TMDB.
- Security: the phone code for wallpapers and the TMDB key is now long and random. Other people on your Wi-Fi cannot guess it.
- The phone pages are in the language of the TV.
- Spotlight shows a streaming service only if the title is on it in your country. Nuvio, Stremio and YouTube trailer always show. To show all the services, turn off **Piume settings → TMDB Spotlight → Only services that have the title**.
- **Piume settings → System → Recent problems** shows the last things that did not work, for example a failed download. You can read them on your phone too.
- The automatic theme waits until you stop using the TV before it changes.
- Updates and weather downloads no longer slow down the rows.

## 1.4

- Plume Launcher is now Piume, a fork by mccoy88f. Piume installs next to Plume and updates from its own releases.
- You can set the weather location by hand. Select **Piume settings → Appearance & wallpapers → Weather location**.
- **Piume settings → System → Home button** is now a switch. Use it to turn the Home button on or off.
- All the text in Piume is now in one file. This makes translation easier.
- Piume is now in Italian. It uses the language of the TV.
- A guided setup makes the Home button open Piume. To do it again, select **Piume settings → System → Set up Home button**.
- A red dot on the Piume settings icon tells you about a new update or new update notes.
- **Piume settings → System → Updates** shows what is new in this version.
- You can put a short app row on the left, in the centre or on the right. Select **Piume settings → Apps row → App row position**.
- Piume settings are now in groups. Select a group to see its settings.
- The top bar has a TV inputs button. Use it to change to an HDMI input.
- **TMDB Spotlight** shows trending films and series above the apps row. Add your TMDB API key in **Piume settings → TMDB Spotlight**.
- Spotlight goes to the next title every 5 seconds. Select **Piume settings → TMDB Spotlight → Next title every** to change the time.
- Use ◀ and ▶ on Spotlight to go to the previous or next title.
- Select an app under Spotlight to open the title in that app. Nuvio and Stremio open the title page.
- **YouTube trailer** under Spotlight plays the trailer from TMDB in your TMDB language. If TMDB has no trailer, it searches YouTube.
- Focused items are easier to see in the light theme and in the dark theme.
- An app with no recommendations shows nothing under the apps row.
- Recommended TV channels are in channel number order.
- Channel cards from a TV app show all of the card. The name on the card does not touch the border.
- When the focused item is below the bottom of the screen, the home screen moves up to show it.
- The TMDB Spotlight image is easier to see. It has less shade at the top and at the bottom.
- Fix: on Android 8.0 and 8.1, the home screen closed when it started.
# Plume Launcher releases

These are the releases of Plume Launcher by aradhyas8, before the fork.

## 1.3

- You can use a folder of photos as your wallpaper. The folder can be on the TV or on a USB drive. Select **Plume settings → Folder photo changes** to set how often the photo changes.
- **Photo fade** removes the dark area at the bottom of your photos.
- You can use your photos as the screensaver. Select **Plume settings → Screensaver → Your photos**.
- **Lock layout** keeps the home screen safe from changes. Set a 4-digit PIN in **Plume settings → Lock layout**. Apps still open without the PIN.
- Hold OK on an app to show **Move**, **Remove from row** and **App info**.
- Hold OK on a Continue Watching card to show **Remove** and **Open app**.
- Continue Watching shows a maximum of three shows from each app.
- **Launcher settings** is now **Plume settings**. Back goes up one page.
- **Remove a photo…** deletes wallpapers that you uploaded.
- The Home button switch opens the correct settings screen on more TVs.

## 1.2.1

- Fix: with **Icon style → Colour**, some app icons stayed grey until Plume started again.

## 1.2

- Plume updates itself. It looks for a new version one time each day. Select **Plume settings → Updates** to install it.
- You can hide apps from the home row. Select **Plume settings → Apps in row**. Hidden apps stay in All apps.
- **App names** shows app names below the tiles: on focus, always or never.
- **Icon style → Colour** shows each logo in the colour of its brand.
- You can add wallpapers from your phone. Select **Add from phone…** in a wallpaper list, then scan the QR code.
- Fix: green areas no longer show in the sky of wallpapers.

## 1.1

- The APK is now 0.4 MB. It was 5.1 MB.
- The Gallery screensaver paintings are now a 4.7 MB download. Select **Plume settings → Screensaver → Gallery** to get them.
- Plume deletes the paintings when you select a different screensaver.
- Fix: Plume did not always find its Home button service.

## 1.0

- First release.
