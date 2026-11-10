#ifndef BATTLE_BOTS_H
#define BATTLE_BOTS_H

#ifdef __cplusplus
extern "C" {
#endif

// Computer players for Battle mode (config key battle=ai). The empty human slots (up to 4 karts in
// total) are filled with karts that are steered by synthetic controller input, exactly as if a
// person held a gamepad. That way the stock Battle code (balloons, items, win check) needs no change
// and no course path data is required.
int BattleBots_IsBot(int playerIndex);
void BattleBots_Prepare(void); // before the karts are spawned: choose bot slots and characters
void BattleBots_Finish(void);  // after the karts are spawned: make the bot karts real players
void BattleBots_Drive(void);   // every frame, after the human inputs are known: fill the bot pads

#ifdef __cplusplus
}
#endif

#endif
