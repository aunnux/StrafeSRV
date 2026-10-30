# StrafeSRV

A DiscordSRV-style plugin for Paper/Spigot that bridges your server with a [Strafe.chat](https://app.strafe.chat/) space.
Built from the Strafe Gateway, REST and Bots docs. **No shaded dependencies** - it uses the JDK's WebSocket/HTTP client and the Gson/Log4j the server already ships.

## Features
- Two-way chat between a Strafe text room and in-game chat
- Join / quit / death / advancement / server start / server stop messages
- Strafe mentions (`<@id>`, `<@&id>`, `<#id>`, custom emoji) rendered readably in game; the member's space nickname and top role are shown
- Optional console room: mirrors the server log and (for an explicit allow-list of Strafe user IDs) runs commands
- Account linking: `/strafe link` -> post `!link CODE` in Strafe; optionally grants a Strafe role to linked players
- "Playing Minecraft | 3/20 online" presence on the bot (gateway op 9)
- Auto-reconnect with backoff, 429/5xx retry, ordered send queue
- Players can never ping anyone on Strafe (`<@id>`, `@everyone`, `@here` are neutralised; the bot also sends `mention_everyone:false`)

## Setup
1. **Create the bot** in Strafe: *Settings -> Developers -> New application -> Add bot*; copy the token.
2. **Add it to your space** (*Add to a space* / install link). Give it at least **View Room, Send Messages, Read Message History** (permissions value `7`), plus **Manage Roles** (`128`) if you want the linked-role feature.
3. **Pick a room with encryption OFF.** Bots cannot read or write E2EE rooms (Strafe's design).
4. Build: `mvn package` -> `target/StrafeSRV-1.0.0.jar` -> drop in `plugins/`.
5. Start the server once, edit `plugins/StrafeSRV/config.yml`: set `strafe.token` and `channels.chat` (quote the room ID), then `/strafe reload`.
6. `/strafe status` tells you whether the bot can see each configured room.

## Commands & permissions
| Command | Permission | |
|---|---|---|
| `/strafe link` / `unlink` | `strafesrv.link` (default: everyone) | link your account |
| `/strafe reload` / `status` | `strafesrv.admin` (default: op) | |
| - | `strafesrv.silent` | player's join/quit/death/advancements are not relayed |

## Console room safety
Console output is forwarded as-is, so it can contain IPs and other sensitive text - use a private room.
Commands from Strafe run **only** for IDs in `console.allowed-user-ids` (empty = read-only), and `console.blocked-commands` can veto specific commands.