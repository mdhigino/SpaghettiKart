#include <libultraship.h>
#include <macros.h>
#include <defines.h>
#include "main.h"
#include "buffers/random.h"
#include "menus.h"
#include "racing/math_util.h"
#include "racing/actors_extended.h"
#include "port/Netplay.h"
#include "enhancements/battle_bots.h"

#define BOT_SLOTS 4

static u8 sBot[BOT_SLOTS];
static s16 sStuck[BOT_SLOTS];      // frames spent (almost) not moving
static s16 sReverse[BOT_SLOTS];    // frames left of backing away from a wall
static s16 sReverseDir[BOT_SLOTS]; // steering used while backing up
static s16 sItemWait[BOT_SLOTS];   // frames the bot has been holding an item
static s16 sWander[BOT_SLOTS];     // frames left of steering towards a random spot instead of an enemy
static s16 sWanderAngle[BOT_SLOTS];

int BattleBots_IsBot(int i) {
    return (i >= 0) && (i < BOT_SLOTS) && sBot[i];
}

static void reset_state(int i) {
    sStuck[i] = 0;
    sReverse[i] = 0;
    sReverseDir[i] = 0;
    sItemWait[i] = 0;
    sWander[i] = 0;
    sWanderAngle[i] = 0;
}

void BattleBots_Prepare(void) {
    s32 humans;
    s32 i;
    s32 c;
    u8 used[8] = { 0 };

    for (i = 0; i < BOT_SLOTS; i++) {
        sBot[i] = 0;
        reset_state(i);
    }
    if ((gModeSelection != BATTLE) || !Netplay_BattleAI()) {
        return;
    }
    // People = phones in the room; a single phone playing alone is one person against the bots.
    humans = Netplay_Active() ? Netplay_PlayerCount() : 1;
    if (humans >= BOT_SLOTS) {
        return;
    }
    for (i = 0; i < humans; i++) {
        used[gCharacterSelections[i] & 7] = 1;
    }
    Netplay_Log("battle bots: people=%d screens=%d", (int) humans, (int) gPlayerCountSelection1);
    for (i = humans; i < BOT_SLOTS; i++) {
        sBot[i] = 1;
        for (c = 0; c < 8; c++) {
            if (!used[c]) {
                break;
            }
        }
        if (c >= 8) {
            c = i;
        }
        used[c] = 1;
        gCharacterSelections[i] = (s8) c;
        Netplay_Log("battle bots: slot %d = character %d", (int) i, (int) c);
    }
}

void BattleBots_Finish(void) {
    s32 i;

    for (i = 0; i < BOT_SLOTS; i++) {
        if (sBot[i]) {
            // Same flags a human kart has at the start of a Battle.
            gPlayers[i].type = PLAYER_EXISTS | PLAYER_START_SEQUENCE | PLAYER_HUMAN;
            Netplay_Log("battle bots: kart %d is now a bot", (int) i);
            reset_state(i);
        }
    }
}

static int is_alive(s32 i) {
    Player* p = &gPlayers[i];
    if (!(p->type & PLAYER_EXISTS)) {
        return 0;
    }
    if (p->type & PLAYER_CINEMATIC_MODE) {
        return 0; // eliminated
    }
    return gPlayerBalloonCount[i] >= 0;
}

static s8 clamp_stick(s32 v) {
    if (v > 75) {
        v = 75;
    }
    if (v < -75) {
        v = -75;
    }
    return (s8) v;
}

static void drive_bot(s32 me) {
    Player* p = &gPlayers[me];
    OSContPad* pad = &gControllerPads[me];
    s32 i;
    s32 target = -1;
    f32 best = 0.0f;
    s16 diff;
    s16 heading;
    f32 dx;
    f32 dz;
    f32 dist2;

    pad->button = 0;
    pad->stick_x = 0;
    pad->stick_y = 0;
    pad->right_stick_x = 0;
    pad->right_stick_y = 0;

    if ((gRaceState != RACE_IN_PROGRESS) || !is_alive(me) || (p->type & PLAYER_START_SEQUENCE)) {
        reset_state(me);
        return;
    }

    // Closest living enemy.
    for (i = 0; i < NUM_PLAYERS; i++) {
        if ((i == me) || !is_alive(i)) {
            continue;
        }
        dx = gPlayers[i].pos[0] - p->pos[0];
        dz = gPlayers[i].pos[2] - p->pos[2];
        dist2 = (dx * dx) + (dz * dz);
        if ((target < 0) || (dist2 < best)) {
            target = i;
            best = dist2;
        }
    }

    // Stuck against a wall (or spun out for a long time): back away for a moment.
    if (p->speed < 1.0f) {
        sStuck[me]++;
    } else if (sStuck[me] > 0) {
        sStuck[me]--;
    }
    if ((sReverse[me] == 0) && (sStuck[me] > 45)) {
        sReverse[me] = 40;
        sReverseDir[me] = ((gTickCounter + me) & 1) ? 60 : -60;
        sStuck[me] = 0;
        sWander[me] = 90;
        sWanderAngle[me] = (s16) (gRandomSeed16 + (me * 12345));
    }
    if (sReverse[me] > 0) {
        sReverse[me]--;
        pad->button = B_BUTTON; // brake/reverse (A not held)
        pad->stick_x = clamp_stick(sReverseDir[me]);
        return;
    }

    if (target < 0) {
        pad->button = A_BUTTON;
        return;
    }

    if (sWander[me] > 0) {
        sWander[me]--;
        heading = sWanderAngle[me];
    } else {
        heading = (s16) (-get_angle_between_two_vectors(p->pos, gPlayers[target].pos));
    }
    diff = (s16) (heading - p->rotation[1]);

    // Positive stick turns the kart towards a larger rotation angle.
    pad->stick_x = clamp_stick(diff / 200);
    pad->button = A_BUTTON;

    // Use items: when an enemy is roughly in front and close, or after holding the item a while.
    if (p->currentItemCopy != ITEM_NONE) {
        sItemWait[me]++;
        if (((sItemWait[me] > 90) && (best < (250.0f * 250.0f)) && (diff > -0x2000) && (diff < 0x2000)) ||
            (sItemWait[me] > 420)) {
            pad->button |= Z_TRIG;
            sItemWait[me] = 0;
        }
    } else {
        sItemWait[me] = 0;
    }
}

void BattleBots_Drive(void) {
    s32 i;

    if ((gModeSelection != BATTLE) || (gGamestate != RACING)) {
        return; // leave the pads alone in menus
    }
    for (i = 0; i < BOT_SLOTS; i++) {
        if (sBot[i]) {
            drive_bot(i);
        }
    }
}
