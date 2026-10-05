---
name: Cobblemon
description: How to play during a Radical Cobblemon Trainers battle while gaming mode is on.
---

This skill teaches you how to participate in a Cobblemon battle with the user. Cobblemon is a mod that adds Pokemon to Minecraft and RCT adds Pokemon trainers.

When a battle starts you are locked as the RCT trainer, not as a Pokemon. A message that a battle has started is a cue to comment with `send_msg`. RCT speaks at the end of the battle, so you do not. The end message still tells you who won and what happened.

`send_msg` is that trainer's line in Minecraft chat. A quiet stretch is only an invitation to comment. Your normal replies stay in this house chat with the user. Do not treat them as speech in the game. The user will usually be looking at the game so avoid house chat unless the user is talking to you that way.

A round starts when a turn message arrives. It says `This is a new choice` and includes what happened since the previous message. That summary is the last turn. You do not need `battle_log` for it. `battle_log` is the full history if you want it.

Read the summary, then `list_actions`, `list_objects`, and `get_state`, then `send_action`. Do not choose before that turn message. If `list_actions` says the turn message has not been delivered yet, wait. Both trainers choose, the turn plays out, and the next message brings the result. If one side has no usable Pokemon left, the battle ends.

`list_actions` lists the moves, with type, power, accuracy, category, PP, and effect, and `switch_out` for each benched Pokemon. `list_objects` with type `item` is the bag. `use_item` uses one of those items. To use it on a benched Pokemon, put that Pokemon's id after the choice number.

`get_state` is the field, and at choice time it has already settled. `get_state` with a Pokemon id is that Pokemon's stats. `send_action` commits one choice. Its payload is the choice number from the turn message. If the result says another choice is still open, choose again before you stop. If you do not choose in time, a legal move or switch is used.

If a Pokemon fainted, the message says you must `switch_out`. That is the only legal choice. The player's battle menu stays hidden until you send the replacement. Do that before you comment.

You do not need to use the Story Book for this game since the user is unlikely to interact with the same trainer more than once.
