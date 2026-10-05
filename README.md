# AGPI: Agent Game Playing Interface

AGPI is a game-agnostic HTTP interface that lets an AI agent play video games. The game (usually a mod) implements the server and a harness such as [Loci](https://github.com/JacobBruce/Loci) connects to it. The agent sees seven generic tools and the game decides what they mean.

This document describes the first version of the AGPI spec. Follow this when making a game AGPI-compatible. See the [CobbleAGPI](https://github.com/JacobBruce/AGPI/cobbleagpi) mod and the accompanying [skill file](https://github.com/JacobBruce/AGPI/cobbleagpi/cobblemon.md) for a working example that can be used with Loci.

## How it works

1. Your game runs a small JSON HTTP server on loopback (default port `24740`).
2. The harness asks about the game (`/v1/hello`) and which skill file teaches the agent how to play.
3. While connected, the agent's seven AGPI tools are forwarded to your server (`/v1/call`).
4. Your game wakes the agent when something happens (`/v1/events`) and can end the session.

The agent talks to the user in the harness chat. Only `send_msg` speaks inside your game.

## Transport

- HTTP/1.1, JSON in and out (UTF-8). Loci only connects to `localhost`, `127.0.0.1` or `::1`.
- Every JSON reply has `"ok": true` or `"ok": false` with an `"error"` string. The agent sees the error text, so make it say how to recover ("Unknown type: foo. Valid: item, body, exit.").
- Loci times out `/v1/call` after 15 seconds and the other endpoints after 4. Answer quickly and do slow work on your own schedule.
- Keep replies compact. Agents read them as text and Loci caps them at around 80 KB.

| Endpoint | Purpose |
|---|---|
| `GET /v1/hello` | Identify the game and its skill. |
| `GET /v1/events` | Hand over (and clear) queued events. |
| `POST /v1/call` | Run one of the seven tools. Body: `{"name":"<tool>","args":{…}}` |

### `GET /v1/hello`

```json
{ "ok": true, "game": "cobbleagpi", "name": "Cobblemon", "skill": "cobblemon.md" }
```

- `game`: short id.
- `name`: display name shown to the user and the agent.
- `skill`: required. A `.md` file name (for example `my-game.md`) or a folder name that contains `SKILL.md`. Loci looks in the shared `~/.loci/skills/` first, then the house's `skills/`. Connecting fails if the skill is not installed by the user.

Loci also calls this every few seconds while the agent is busy. You can use it as a "Loci is still listening" signal.

### `GET /v1/events`

Return everything queued since the last call and clear the queue.

```json
{ "ok": true, "events": [ { "type": "wake", "reason": "your_turn", "text": "It is your turn." } ] }
```

- `type: "wake"` starts an agent turn. Loci shows it as `The game says (reason): text`.
- `type: "end"` ends gaming mode and disconnects the harness. If a batch contains an `end`, it wins.
- `reason` is a short machine label. `text` is what the agent reads, so put the facts it needs to act in it.

Loci polls once a second while the agent is idle and holds off while the agent is mid-turn, so events queue up on your side. Merge or order them however suits the game. If the game stops answering for 3 polls in a row, the Game Console shows a warning.

## The seven tools

Tool arguments arrive in `args`. Unless noted, every argument is optional. Tool replies can carry any extra fields you like next to `ok`.

### `lock_body`

Choose which body the agent controls. A body is whoever `send_action` and `send_msg` act as: a player, a follower, a etc.

When possible you should lock automatically so the agent doesn't need to. Use the skill to teach agents how your game uses body locking.

Args: `target` (string)
- No `target`: describe the current lock and the other bodies that are available.
- With a `target`: lock it. An unknown `target` is an error, and the previous lock stays.

Example response:
```json
{ "ok": true, "connected": true,
  "selected": { "id": "guide", "name": "Guide" },
  "available": [ { "id": "guide", "name": "Guide" } ] }
```

### `list_objects`

Scan what is around the locked body and/or use it to provide a list of inventory items. What it returns depends on the type of game.

Args: `type` (string), `limit` (integer).
- No `type`: return the default scan plus a `types` catalog so the agent knows what else it can ask for.
- With a `type`: return only that scan, without the catalog. `type` selects a kind of scan (nearby items, inventory items, enemies…).
- Clamp `limit` to something sane. An unknown `type` is an error that lists the valid ones.

Example response:
```json
{ "ok": true, "type": "item", "room": { "id": "practice", "name": "Practice room" },
  "items": [ { "id": "bench", "name": "Bench" } ],
  "types": [ { "id": "item", "when": "Things in the room." }, { "id": "exit", "when": "Ways out." } ] }
```

Every object needs a stable `id`. The agent passes ids back as `target` to other tools.

### `list_actions`

List legal actions the locked body can make at the time when this tool is called.

Args: `target` (an `id` from `list_objects`).
- No `target`: verbs that need no object (`wait`, `look`, `go`…).
- With `target`: verbs that work on that object. Unknown `target` is an error.

Example response:
```json
{ "ok": true, "target": "door",
  "actions": [ { "action": "open", "how": "send_action with action \"open\" and target \"door\".",
                 "when": "You want to go through. The door must be unlocked." } ] }
```

Each action has `action` (the verb), `how` (exactly how to call `send_action`, including payload format) and `when` (when it makes sense to use it).

### `send_action`

Perform an action with the locked body. Reply with what happened in a `text` field the agent can read, and fail with a useful `error` when the action is illegal.

Args: `action` (required), `target`, `payload`. All three are strings.
- No `target`: perform general action (`wait`, `look`, `go`…).
- With a `target`: perform action on the targeted object.
- If `payload` should be JSON, say so in `how` and expect it as a string.

Example response:
```json
{ "ok": true, "action": "examine", "target": "bench", "text": "A plain wooden bench." }
```

**Reserved verbs**: while a game is connected Loci handles `search_web`, `get_web_page`, `notify_user`, `open_poll`, `vote`, `close_poll`, `add_notice`, `remove_file` and `set_status` itself and blocks `download_file`, `dm`, `send_mail`, `set_alarm` and `stop_alarm`. Do not use those names for your own verbs.

### `get_state`

A snapshot of the current game state. The Cobblemon mod uses this to provide a snapshot of the play field during a battle.

Args: `query` (string).

- No `query`: a useful default snapshot (where the agent is, what it is controlling, whatever matters most).
- With a `query`: a named slice, such as `room`, `player` or an object id. An unknown `query` is an error that lists the valid ones.

The response shape is up to you. Keep field names consistent so the agent can compare snapshots from turn to turn.

### `send_msg`

Speak as the locked body. Show the line wherever your game shows speech (chat, bubbles…). Standard agent messages stay in the harness chat.

Args: `text` (required), `to` (the recipient, if your game has addressing).

On success respond with:
```json
{ "ok": true, "delivered": true }
```

If your game has moments where the agent should not be able to speak, return an error.

### `get_screen`

Allows the agent to see what is happening in the game. The agent must have vision capabilities for this to work.

This tool has a `size` argument the agent can use but Loci calls it with no arguments and does the scaling itself.

All you need to do is save a screenshot (PNG or JPEG) to a file on the same machine and reply with its absolute path:

```json
{ "ok": true, "path": "/tmp/mygame/screen.png" }
```

If your game cannot capture the screen, reply `{ "ok": false, "error": "This game cannot capture the screen." }`.

## The skill file

The skill is a short markdown file that tells the agent how to play your game. Loci tells the agent to read it when gaming mode starts. Cover the things the tools cannot say:

- Who the agent is and what the player expects of it.
- When to act, when to comment with `send_msg`, and when to stay quiet.
- The loop for your game (what a "turn" is, which tool to call first).
- Anything unusual about your payloads or ids.
- How to use the Story Book in Loci if your game requires it.

```md
---
name: Skyrim companion
description: How to play as a Skyrim companion while gaming mode is on.
---

This skill teaches you how to play Skyrim with the user by controlling followers. ...
```

See [`cobbleagpi/cobblemon.md`](cobbleagpi/cobblemon.md) for a real example.

A game like Skyrim will benefit from using the Story Book object in Loci. It allows the agent to keep track of game events and character history.

Your skill should tell the agent what information to record and the directory structure it should use to record that information (using markdown files). Each house in Loci has a `stories` folder for this purpose.

The recommended directory structure is `stories/<campaign>/HISTORY.md` for world events, `stories/<campaign>/<character>/HISTORY.md` for character events, and `stories/<campaign>/<character>/PROFILE.md` for character facts, backstory, relationships, etc.

## Design tips

- **Wake the agent, do not make it poll.** Put the result of the last turn in the wake `text`.
- **Be strict about legality.** An error is cheaper than a silently ignored action.
- **Let the player stay in control.** If the agent takes too long, fall back to something safe. For example, the Cobblemon mod picks a legal move after a timeout.
- **Make `end` mean the session is over.** If the game can continue with another encounter, use a `wake` and stay connected.