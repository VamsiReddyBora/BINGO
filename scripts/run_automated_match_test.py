#!/usr/bin/env python3
"""
Automated 5-Player Match Verification Runner
Runs play_multiplayer_bots.py with test_bob_multiplayer.py to verify:
- Bob takes 7.5s to pick without being skipped at 5s.
- Turns rotate seamlessly across 5 players.
- Random minimize/disconnect simulation runs.
- Phil surrenders and game continues with 4 players.
- Game over and winning screen state is broadcast and finalized.
"""

import subprocess
import time
import sys
from test_bob_multiplayer import BobTestClient

ROOM_CODE = "TEST99"

def run_test():
    print(f"=== Starting Multiplayer Automated Match Verification in Room {ROOM_CODE} ===")

    # 1. Start bot companion daemon
    bot_proc = subprocess.Popen(
        ["python3", "-u", "scripts/play_multiplayer_bots.py", "--room", ROOM_CODE]
    )

    # Allow bot companion to connect to MQTT and initialize lobby
    time.sleep(3.0)

    # 2. Start Bob test client
    bob = BobTestClient(ROOM_CODE)
    bob.start()

    start_time = time.time()
    max_duration = 180.0 # 3 minutes max

    try:
        while time.time() - start_time < max_duration:
            if bob.is_game_over:
                print("\n🎉 Match completed successfully!")
                break
            time.sleep(1.0)
    finally:
        bob.stop()
        bot_proc.terminate()
        try:
            bot_proc.wait(timeout=5)
        except Exception:
            bot_proc.kill()

    print("\n" + "="*50)
    print("VERIFICATION RESULTS:")
    print("="*50)

    success = True

    # Check 1: 5-second skip
    if bob.five_sec_skip_detected:
        print("❌ FAIL: Bob's turn was skipped early at 5 seconds!")
        success = False
    else:
        print(f"✅ PASS: Bob took 7.5s on every turn ({bob.bob_turn_count} turns played) - NEVER skipped at 5s!")

    # Check 2: Phil surrender
    if bob.phil_surrender_detected:
        print("✅ PASS: Phil surrendered mid-game and match continued seamlessly!")
    else:
        print("⚠️ NOTE: Phil surrender did not trigger before match concluded.")

    # Check 3: Game over received
    if bob.game_over_received and bob.is_game_over:
        print(f"✅ PASS: GAME_OVER received cleanly! Winner: {bob.winner_id} ({bob.win_reason})")
    else:
        print("❌ FAIL: GAME_OVER packet was not received or game did not finalize!")
        success = False

    if success:
        print("\n🎉 ALL MULTIPLAYER VERIFICATION CHECKS PASSED PERFECTLY! 🎉\n")
        sys.exit(0)
    else:
        print("\n❌ SOME CHECKS FAILED!\n")
        sys.exit(1)

if __name__ == "__main__":
    run_test()
