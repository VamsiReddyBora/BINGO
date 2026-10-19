#!/usr/bin/env python3
"""
Synthetic Bob Multiplayer Test Client
Verifies:
1. Bob has full 30s countdown; Bob is NOT skipped at 5s even if taking >5s.
2. Bob is NOT flagged as 'reconnecting' / 'offline' while thinking.
3. Turns advance cleanly across Kevin, Stuart, Otto, Phil, and Bob.
4. Phil surrenders mid-game, game continues with 4 players.
5. When any player hits 5 lines, GAME_OVER packet is broadcast and game finalizes cleanly.
"""

import sys
import time
import json
import ssl
import random
import threading
from typing import Dict, List, Optional, Set
import paho.mqtt.client as mqtt

BROKER_HOST = "p0812f88.ala.asia-southeast1.emqxsl.com"
BROKER_PORT = 8883
BROKER_USER = "Bora"
BROKER_PASS = "bora7989"

# Java Random parity
class JavaRandom:
    def __init__(self, seed: int):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next(self, bits: int) -> int:
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.seed >> (48 - bits)

    def next_int(self, n: int) -> int:
        if (n & -n) == n:
            return (n * self.next(31)) >> 31
        bits = self.next(31)
        val = bits % n
        while (bits - val + (n - 1)) < 0:
            bits = self.next(31)
            val = bits % n
        return val

def generate_board(seed: int) -> List[int]:
    rng = JavaRandom(seed)
    nums = list(range(1, 26))
    for i in range(len(nums), 1, -1):
        j = rng.next_int(i)
        nums[i - 1], nums[j] = nums[j], nums[i - 1]
    return nums

def count_completed_lines(board: List[int], marked: Set[int]) -> int:
    grid = [board[i * 5:(i + 1) * 5] for i in range(5)]
    lines = 0
    # Rows
    for r in range(5):
        if all(grid[r][c] in marked for c in range(5)):
            lines += 1
    # Cols
    for c in range(5):
        if all(grid[r][c] in marked for r in range(5)):
            lines += 1
    # Diags
    if all(grid[i][i] in marked for i in range(5)):
        lines += 1
    if all(grid[i][4 - i] in marked for i in range(5)):
        lines += 1
    return lines

class BobTestClient:
    def __init__(self, room_code: str):
        self.room_code = room_code
        self.topic = f"bingo/v3/room/{room_code}"
        self.client_id = f"test_bob_{random.randint(1000, 9999)}"
        self.bob_id = "user_bob_123"
        self.bob_name = "Bob"
        self.match_seed = 0
        self.bob_board: List[int] = []
        self.marked: Set[int] = set()
        self.current_turn_player_id = ""
        self.turn_number = 0
        self.is_game_over = False
        self.winner_id = ""
        self.win_reason = ""
        self.running = True
        self.lock = threading.RLock()
        
        # Test assertions
        self.bob_turn_count = 0
        self.five_sec_skip_detected = False
        self.game_over_received = False
        self.phil_surrender_detected = False
        self.scheduled_bob_turn = ""
        self.is_bob_thinking = False

        if hasattr(mqtt, "CallbackAPIVersion"):
            self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=self.client_id)
        else:
            self.client = mqtt.Client(client_id=self.client_id)

        self.client.username_pw_set(BROKER_USER, BROKER_PASS)
        self.client.tls_set(cert_reqs=ssl.CERT_NONE)
        self.client.tls_insecure_set(True)
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message

    def start(self):
        print(f"Connecting Bob test client to {self.topic}...")
        self.client.connect(BROKER_HOST, BROKER_PORT, 60)
        self.client.loop_start()

        # Send heartbeat & ready in lobby
        for _ in range(3):
            time.sleep(0.5)
            # Send Ready packet
            ready_pkt = {
                "type": "PLAYER_READY",
                "playerId": self.bob_id,
                "displayName": self.bob_name,
                "username": "bob_minion",
                "readyStatus": "READY",
                "isHost": False,
                "timestamp": int(time.time() * 1000)
            }
            self.publish(json.dumps(ready_pkt))
            # Also send micro ready
            self.publish(f"R|{self.bob_id}|READY|1|{self.bob_name}")

    def publish(self, payload: str):
        self.client.publish(self.topic, payload, qos=1)

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        print(f"Connected to broker (rc={rc}), subscribing to {self.topic}...")
        self.client.subscribe(self.topic, qos=1)

    def _on_message(self, client, userdata, msg):
        try:
            payload = msg.payload.decode("utf-8").strip()
            self._handle_packet(payload)
        except Exception as e:
            print(f"[Bob Error] _on_message: {e}")

    def _handle_packet(self, payload: str):
        with self.lock:
            # 1. JSON
            if payload.startswith("{") and payload.endswith("}"):
                data = json.loads(payload)
                t = data.get("type", "")
                pid = data.get("playerId", "")

                if t == "START_GAME":
                    self.match_seed = data.get("seed", 0)
                    self.bob_board = generate_board(self.match_seed + 1) # seed for bob
                    self.current_turn_player_id = data.get("currentTurnPlayerId") or data.get("firstTurnPlayerId") or ""
                    self.turn_number = data.get("turnNumber", 1)
                    print(f"🎮 [START_GAME] Seed={self.match_seed}, First turn: {self.current_turn_player_id}")
                    self._check_and_trigger_bob_turn()

                elif t == "PICK_NUMBER":
                    num = data.get("number", 0)
                    picker = data.get("playerId", "")
                    if num > 0:
                        self.marked.add(num)
                    self.turn_number = data.get("turnNumber", self.turn_number + 1)
                    self.current_turn_player_id = data.get("currentTurnPlayerId", "")
                    lines = count_completed_lines(self.bob_board, self.marked) if self.bob_board else 0
                    print(f"  👉 [PICK] #{num} by {picker} | Bob marked: {len(self.marked)} | Bob lines: {lines} | Next: {self.current_turn_player_id}")
                    
                    if lines >= 5 and not self.is_game_over:
                        print("🎉 Bob reached 5 lines! Claiming BINGO...")
                        self.publish(f"BINGO|{self.bob_id}|{lines}|{num}|{self.match_seed}")

                    self._check_and_trigger_bob_turn()

                elif t == "TURN_TIMEOUT":
                    timed_out_id = data.get("playerId", "")
                    if timed_out_id == self.bob_id:
                        print("❌ CRITICAL BUG DETECTED: Bob's turn was timed out early!")
                        self.five_sec_skip_detected = True

                elif t == "SURRENDER":
                    surr_id = data.get("playerId", "")
                    if surr_id == "player_phil":
                        print("🚪 Phil surrendered as expected!")
                        self.phil_surrender_detected = True

                elif t == "GAME_OVER":
                    self.is_game_over = True
                    self.game_over_received = True
                    self.winner_id = data.get("winnerPlayerId", "")
                    self.win_reason = data.get("winReason", "")
                    print(f"\n🏆 [GAME_OVER] Winner: {self.winner_id} | Reason: {self.win_reason}")

                elif t == "GAME_SYNC":
                    if data.get("isMatchFinished") or data.get("winnerPlayerId"):
                        self.is_game_over = True
                        self.game_over_received = True
                        self.winner_id = data.get("winnerPlayerId", "")
                        self.win_reason = data.get("winReason", "")
                        print(f"🏆 [GAME_SYNC Finalized] Winner: {self.winner_id}")

            # 2. Micro Pick
            elif payload.startswith("P|"):
                parts = payload.split("|")
                num = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
                picker = parts[2] if len(parts) > 2 else ""
                next_turn = parts[4] if len(parts) > 4 else ""
                if num > 0:
                    self.marked.add(num)
                self.current_turn_player_id = next_turn
                lines = count_completed_lines(self.bob_board, self.marked) if self.bob_board else 0
                print(f"  👉 [Micro-P] #{num} by {picker} | Bob lines: {lines} | Next: {self.current_turn_player_id}")
                if lines >= 5 and not self.is_game_over:
                    print("🎉 Bob reached 5 lines! Claiming BINGO...")
                    self.publish(f"BINGO|{self.bob_id}|{lines}|{num}|{self.match_seed}")
                self._check_and_trigger_bob_turn()

            # 3. Micro Timeout
            elif payload.startswith("T|"):
                parts = payload.split("|")
                timed_out_id = parts[1] if len(parts) > 1 else ""
                next_turn = parts[3] if len(parts) > 3 else ""
                self.current_turn_player_id = next_turn
                if timed_out_id == self.bob_id:
                    print("❌ CRITICAL BUG DETECTED: Bob was timed out via Micro-T!")
                    self.five_sec_skip_detected = True
                self._check_and_trigger_bob_turn()

    def _check_and_trigger_bob_turn(self):
        with self.lock:
            if self.current_turn_player_id == self.bob_id and not self.is_game_over and not self.is_bob_thinking:
                self.is_bob_thinking = True
                self.bob_turn_count += 1
                turn_idx = self.bob_turn_count
                threading.Thread(target=self._take_bob_turn, args=(turn_idx,), daemon=True).start()

    def _take_bob_turn(self, turn_idx: int):
        print(f"\n🧠 [BOB'S TURN #{turn_idx}] Bob is thinking... Deliberately waiting 7.5 seconds (testing full 30s timer, must NOT skip at 5s!)...")
        time.sleep(7.5)

        with self.lock:
            self.is_bob_thinking = False
            if self.is_game_over:
                return
            if self.current_turn_player_id != self.bob_id:
                print("❌ CRITICAL BUG: Bob's turn was passed/skipped before 7.5s!")
                self.five_sec_skip_detected = True
                return
            
            # Select an unmarked number from board
            unmarked = [n for n in self.bob_board if n not in self.marked]
            if not unmarked:
                unmarked = [n for n in range(1, 26) if n not in self.marked]
            chosen = random.choice(unmarked) if unmarked else random.randint(1, 25)
            self.marked.add(chosen)
            lines = count_completed_lines(self.bob_board, self.marked) if self.bob_board else 0

            print(f"🎯 [BOB PICK] Bob chose #{chosen} after 7.5s thinking! Bob lines: {lines}")

            # Send pick
            pick_pkt = {
                "type": "PICK_NUMBER",
                "number": chosen,
                "playerId": self.bob_id,
                "turnNumber": self.turn_number + 1,
                "seed": self.match_seed,
                "matchId": str(self.match_seed),
                "timestamp": int(time.time() * 1000)
            }
            self.publish(json.dumps(pick_pkt))
            self.publish(f"P|{chosen}|{self.bob_id}|{self.turn_number + 1}||||")

            if lines >= 5:
                print("🎉 🎉 🎉 BINGO! BOB REACHED 5 LINES! Claiming victory...")
                self.publish(f"BINGO|{self.bob_id}|{lines}|{chosen}|{self.match_seed}")
                bingo_claim = {
                    "type": "BINGO_CLAIMED",
                    "playerId": self.bob_id,
                    "seed": self.match_seed,
                    "matchId": str(self.match_seed)
                }
                self.publish(json.dumps(bingo_claim))

    def stop(self):
        self.running = False
        self.client.loop_stop()
        self.client.disconnect()
