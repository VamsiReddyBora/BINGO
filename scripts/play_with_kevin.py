#!/usr/bin/env python3
"""
Play with Kevin - Virtual AI Companion for Bingo Multiplayer
Allows the user to play a 1v1 multiplayer Bingo match against Kevin.

Features:
- Kevin's display name is "Kevin" (so the system chat displays "👑 Kevin is the host").
- Supports mid-game exit simulation (abrupt drop, HOST_LEFT packet, or staged disconnect).
- Real-time in-game chat reactions and human-paced number picks.
- Full compatibility with Bingo v1.5.0 protocol (3-second countdown, micro-payloads, host crowns).
"""

import sys
import os
import time
import json
import random
import threading
import ssl
import urllib.request
import urllib.parse
from typing import Dict, List, Optional, Set
import paho.mqtt.client as mqtt

# --- Configuration ---
BROKER_HOST = "p0812f88.ala.asia-southeast1.emqxsl.com"
BROKER_PORT = 8883
BROKER_USER = "Bora"
BROKER_PASS = "bora7989"

KEYVALUE_API_URL = "https://keyvalue.immanuel.co/api/KeyVal"
KEYVALUE_APP_KEY = "2464j24f"

KEVIN_ID = "user_kevin"
KEVIN_NAME = "Kevin"
KEVIN_USERNAME = "kevin"

# --- Deterministic Random Shuffler ---
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

def java_shuffle(lst: list, seed: int) -> list:
    rng = JavaRandom(seed)
    shuffled = list(lst)
    for i in range(len(shuffled), 1, -1):
        j = rng.next_int(i)
        shuffled[i - 1], shuffled[j] = shuffled[j], shuffled[i - 1]
    return shuffled

def count_completed_lines(board: List[int], marked: Set[int]) -> int:
    lines = 0
    # Rows
    for r in range(5):
        if all(board[r * 5 + c] in marked for c in range(5)):
            lines += 1
    # Cols
    for c in range(5):
        if all(board[r * 5 + c] in marked for r in range(5)):
            lines += 1
    # Diag 1
    if all(board[i * 5 + i] in marked for i in range(5)):
        lines += 1
    # Diag 2
    if all(board[i * 5 + (4 - i)] in marked for i in range(5)):
        lines += 1
    return lines

class KevinCompanion:
    def __init__(
        self,
        room_code: Optional[str] = None,
        is_host: bool = True,
        simulate_exit: bool = True,
        exit_turn: Optional[int] = None,
        exit_mode: str = "staged"
    ):
        self.is_host = is_host
        self.room_code = (room_code or self._generate_room_code()).upper()
        self.topic = f"bingo/v3/room/{self.room_code}"
        self.running = True
        self.heartbeats_enabled = True
        self.lock = threading.Lock()

        # Mid-game Exit Simulation
        self.simulate_exit = simulate_exit
        self.exit_turn = exit_turn or random.randint(5, 7)
        self.exit_mode = exit_mode  # "away", "offline", "surrender"
        self.has_simulated_away = False
        self.is_currently_away = False

        # Network
        self.client_id = f"sim_kevin_{random.randint(1000, 9999)}"
        self.client = mqtt.Client(client_id=self.client_id, protocol=mqtt.MQTTv311)
        self.client.username_pw_set(BROKER_USER, BROKER_PASS)
        self.client.tls_set(cert_reqs=ssl.CERT_NONE)
        self.client.tls_insecure_set(True)

        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.on_disconnect = self._on_disconnect

        # Match State
        self.in_game = False
        self.match_seed = 0
        self.board_size = 5
        self.turn_number = 1
        self.current_turn_player_id = ""
        self.turn_order: List[str] = []
        self.picked_numbers: List[int] = []
        self.picked_by: List[str] = []
        self.marked: Set[int] = set()
        self.board: List[int] = []
        self.opponent_id = ""
        self.opponent_name = "Opponent"
        self.opponent_ready = False
        self.match_started = False
        self.pick_thread = None

    def _generate_room_code(self) -> str:
        # Generates a clean 6-character room code starting with KV
        chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return "KV" + "".join(random.choice(chars) for _ in range(4))

    def start(self):
        print(f"\n=======================================================")
        print(f"  🤖 KEVIN COMPANION (v1.5.0) IS READY TO PLAY!")
        print(f"=======================================================")
        print(f"Name:        {KEVIN_NAME}")
        print(f"Role:        {'Host 👑' if self.is_host else 'Guest'}")
        print(f"Room Code:   {self.room_code}")
        print(f"Topic:       {self.topic}")
        print(f"Broker:      {BROKER_HOST}:{BROKER_PORT}")
        if self.simulate_exit:
            print(f"Exit Test:   Will simulate exit on Turn {self.exit_turn} (mode: {self.exit_mode})")
        print(f"=======================================================\n")

        print("[Network] Connecting to MQTT broker...")
        self.client.connect(BROKER_HOST, BROKER_PORT, 60)
        self.client.loop_start()

        # Register room if hosting
        if self.is_host:
            self._register_room_metadata()

        # Start background heartbeat thread
        self.heartbeat_thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        self.heartbeat_thread.start()

    def _register_room_metadata(self):
        """Registers room session on MQTT retained topic and KeyValue API so app finds it."""
        session = {
            "roomCode": self.room_code,
            "hostId": KEVIN_ID,
            "hostUsername": KEVIN_USERNAME,
            "hostDisplayName": KEVIN_NAME,
            "status": "WAITING",
            "boardSize": 5,
            "createdAt": int(time.time() * 1000),
            "lastHeartbeat": int(time.time() * 1000),
            "players": [
                {
                    "id": KEVIN_ID,
                    "displayName": KEVIN_NAME,
                    "username": KEVIN_USERNAME,
                    "isHost": True,
                    "lobbyReadyStatus": "READY"
                }
            ],
            "currentSeed": 0,
            "isManualBoard": False,
            "isDynamicBoard": False
        }
        json_str = json.dumps(session)
        meta_topic = f"bingo/v3/room_meta/{self.room_code}"
        self.client.publish(meta_topic, json_str, qos=1, retain=True)
        print(f"[Registry] Published room metadata to retained topic '{meta_topic}'")

        # Also register to KeyValue API for full resilience
        try:
            url = f"{KEYVALUE_API_URL}/UpdateValue/{KEYVALUE_APP_KEY}/room_{self.room_code}?value={urllib.parse.quote(json_str)}"
            req = urllib.request.Request(url, data=b"", headers={"Content-Length": "0"}, method="POST")
            with urllib.request.urlopen(req, timeout=3.0) as resp:
                print(f"[Registry] Registered room {self.room_code} on KeyValue cloud registry (status={resp.status})")
        except Exception as e:
            print(f"[Registry] KeyValue registration note: {e} (MQTT retained is primary fallback)")

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        if rc == 0:
            print(f"[Network] Connected to broker successfully!")
            # 1. Subscribe to room topic
            client.subscribe(self.topic, qos=1)
            print(f"[Network] Subscribed to room topic: {self.topic}")

            # 2. Subscribe to personal invite topic
            invite_topic = f"bingo/v3/invites/{KEVIN_USERNAME}"
            client.subscribe(invite_topic, qos=1)
            print(f"[Network] Subscribed to personal invites on: {invite_topic}")

            # Send initial presence packet in room
            time.sleep(0.3)
            self._send_presence()
        else:
            print(f"[Network] Connection failed with rc={rc}")

    def _on_disconnect(self, client, userdata, *args, **kwargs):
        if self.running:
            print("[Network] Disconnected from broker. Auto-reconnecting...")

    def _send_presence(self):
        if not self.heartbeats_enabled:
            return
        now_ms = int(time.time() * 1000)
        is_host_flag = 1 if self.is_host else 0
        status_flag = "READY"
        hb = f"H|{KEVIN_ID}|{KEVIN_NAME}|{is_host_flag}|{now_ms}|{KEVIN_USERNAME}||{status_flag}|1|2"
        self.publish(hb)
        ready_micro = f"R|{KEVIN_ID}|READY|{KEVIN_USERNAME}|{KEVIN_NAME}|1|kevin_instance|1|1|2"
        self.publish(ready_micro)

    def _heartbeat_loop(self):
        while self.running:
            try:
                time.sleep(2.0)
                if not self.heartbeats_enabled:
                    continue
                if not self.in_game:
                    self._send_presence()
                else:
                    # In game heartbeat
                    mhb = f"MHB|1|{self.match_seed}|ACTIVE|{KEVIN_ID}|{self.opponent_id}|kevin_instance|{self.turn_number}|none|{int(time.time()*1000)}"
                    self.publish(mhb)
            except Exception:
                pass

    def publish(self, payload: str):
        if self.client and self.running:
            self.client.publish(self.topic, payload, qos=1)

    def send_chat(self, text: str):
        pkt = {
            "type": "CHAT_MESSAGE",
            "playerId": KEVIN_ID,
            "displayName": text,
            "username": KEVIN_NAME,
            "seed": self.match_seed,
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(pkt))
        print(f"💬 [Kevin Chat] \"{text}\"")

    def _on_message(self, client, userdata, msg):
        try:
            topic = msg.topic
            payload = msg.payload.decode("utf-8").strip()

            # Handle invite
            if topic == f"bingo/v3/invites/{KEVIN_USERNAME}":
                self._handle_invite(payload)
                return

            if topic == self.topic:
                self._handle_room_message(payload)
        except Exception as e:
            print(f"[Error] on_message exception: {e}")

    def _handle_invite(self, payload: str):
        try:
            data = json.loads(payload)
            target_room = data.get("roomCode")
            sender = data.get("fromDisplayName", data.get("fromUsername", "Someone"))
            print(f"\n📩 [INVITE RECEIVED] {sender} invited Kevin to room: {target_room}!")
            if target_room and target_room.upper() != self.room_code:
                print(f"[Switch Room] Switching Kevin to {target_room.upper()}...")
                self.client.unsubscribe(self.topic)
                self.room_code = target_room.upper()
                self.topic = f"bingo/v3/room/{self.room_code}"
                self.is_host = False
                self.client.subscribe(self.topic, qos=1)
                time.sleep(0.3)
                self._send_presence()
                self.send_chat(f"Hey {sender}! Thanks for the invite, I'm ready! 👑")
        except Exception as e:
            print(f"[Invite Error] {e}")

    def _handle_room_message(self, payload: str):
        with self.lock:
            # 1. Heartbeat from opponent
            if payload.startswith("H|"):
                parts = payload.split("|")
                p_id = parts[1] if len(parts) > 1 else ""
                p_name = parts[2] if len(parts) > 2 else ""
                p_ready = parts[7] if len(parts) > 7 else ""
                if p_id and p_id != KEVIN_ID:
                    if not self.opponent_id:
                        self.opponent_id = p_id
                        self.opponent_name = p_name or "Player"
                        print(f"\n👋 [LOBBY] Player '{self.opponent_name}' ({p_id}) joined room {self.room_code}!")
                        self.send_chat(f"Welcome {self.opponent_name}! Ready to play? 👑")

                    if p_ready == "READY":
                        if not self.opponent_ready:
                            print(f"✅ [LOBBY] {self.opponent_name} is READY!")
                            self.opponent_ready = True
                            if self.is_host and not self.match_started:
                                threading.Thread(target=self._host_start_countdown, daemon=True).start()
                    elif p_ready in ("NOT_READY", "IDLE"):
                        self.opponent_ready = False

            # 2. Ready micro
            elif payload.startswith("R|"):
                parts = payload.split("|")
                p_id = parts[1] if len(parts) > 1 else ""
                status = parts[2] if len(parts) > 2 else ""
                if p_id and p_id != KEVIN_ID:
                    if status == "READY":
                        if not self.opponent_ready:
                            print(f"✅ [LOBBY] {self.opponent_name} tapped READY!")
                            self.opponent_ready = True
                            if self.is_host and not self.match_started:
                                threading.Thread(target=self._host_start_countdown, daemon=True).start()
                    else:
                        self.opponent_ready = False

            # 3. Start game (guest receives)
            elif payload.startswith("S|"):
                parts = payload.split("|")
                seed = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else 12345
                first_turn = parts[1] if len(parts) > 1 else ""
                self._start_game(seed, first_turn)

            elif payload.startswith("{"):
                try:
                    data = json.loads(payload)
                    pkt_type = data.get("type")
                    if pkt_type == "START_GAME":
                        if not self.is_host:
                            seed = data.get("seed", 12345)
                            first_turn = data.get("currentTurnPlayerId", "")
                            # Guest also waits for 3s countdown before starting
                            threading.Thread(target=self._guest_start_countdown, args=(seed, first_turn), daemon=True).start()
                    elif pkt_type == "PICK_NUMBER":
                        num = data.get("number", 0)
                        picker = data.get("playerId", "")
                        turn_num = data.get("turnNumber", self.turn_number)
                        next_turn = data.get("currentTurnPlayerId", "")
                        self._on_pick_received(num, picker, turn_num, next_turn)
                    elif pkt_type == "CHAT_MESSAGE":
                        sender = data.get("username", "Opponent")
                        text = data.get("displayName", "")
                        if data.get("playerId") != KEVIN_ID and text:
                            print(f"💬 [{sender}] \"{text}\"")
                except Exception:
                    pass

            # 4. Pick micro
            elif payload.startswith("P|"):
                parts = payload.split("|")
                num = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
                picker = parts[2] if len(parts) > 2 else ""
                turn_num = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else self.turn_number
                next_turn = parts[4] if len(parts) > 4 else ""
                self._on_pick_received(num, picker, turn_num, next_turn)

    def _host_start_countdown(self):
        """Host initiates 3-second synchronized countdown and broadcasts START_GAME."""
        self.match_started = True
        seed = int(time.time() * 1000) % 100000000
        first_turn = random.choice([KEVIN_ID, self.opponent_id])

        print(f"\n🚀 [HOST] Both players READY! Starting 3-second countdown...")
        self.send_chat("All ready! Game begins in 3... 2... 1... 🚀")

        start_pkt = {
            "type": "START_GAME",
            "boardSize": 5,
            "seed": seed,
            "matchId": str(seed),
            "protocolVersion": 2,
            "playerId": KEVIN_ID,
            "currentTurnPlayerId": first_turn,
            "isManualBoard": False,
            "isDynamicBoard": False,
            "players": [
                {"id": KEVIN_ID, "displayName": KEVIN_NAME, "username": KEVIN_USERNAME, "isHost": True},
                {"id": self.opponent_id, "displayName": self.opponent_name, "username": self.opponent_name.lower(), "isHost": False}
            ]
        }
        json_start = json.dumps(start_pkt)
        self.publish(json_start)
        # Fast broadcast burst
        time.sleep(0.15)
        self.publish(json_start)

        # 3-second background sync countdown
        for c in [3, 2, 1]:
            print(f"  ⏱️  Countdown: {c}...")
            time.sleep(1.0)
        print("  🚀 GAME BEGINS NOW!")

        self._start_game(seed, first_turn)

    def _guest_start_countdown(self, seed: int, first_turn: str):
        print(f"\n🚀 [GUEST] Host started game! Running 3-second synchronized countdown...")
        for c in [3, 2, 1]:
            print(f"  ⏱️  Countdown: {c}...")
            time.sleep(1.0)
        print("  🚀 GAME BEGINS NOW!")
        self._start_game(seed, first_turn)

    def _start_game(self, seed: int, first_turn: str):
        if self.in_game:
            return
        self.in_game = True
        self.match_seed = seed
        self.turn_number = 1
        self.picked_numbers.clear()
        self.picked_by.clear()
        self.marked.clear()

        # Generate Kevin's board
        base_numbers = list(range(1, 26))
        self.board = java_shuffle(base_numbers, seed)

        # Deterministic turn order
        participants = sorted([KEVIN_ID, self.opponent_id])
        self.turn_order = java_shuffle(participants, seed)
        self.current_turn_player_id = first_turn or self.turn_order[0]

        print("\n" + "="*60)
        print("🎮 🎮 🎮  MATCH STARTED!  🎮 🎮 🎮")
        print(f"Room Code:       {self.room_code}")
        print(f"Match Seed:      {self.match_seed}")
        print(f"Kevin Board:     {self.board[:5]} ... (25 cells)")
        print(f"First Turn:      {KEVIN_NAME if self.current_turn_player_id == KEVIN_ID else self.opponent_name}")
        if self.simulate_exit:
            print(f"Exit Trigger:    Will simulate exit on Turn {self.exit_turn} (mode: {self.exit_mode})")
        print("="*60 + "\n")

        self._check_and_trigger_turn()

    def _on_pick_received(self, num: int, picker: str, turn_num: int, next_turn: str):
        if num > 0 and num not in self.picked_numbers:
            self.picked_numbers.append(num)
            self.picked_by.append(picker)
            self.marked.add(num)

        self.turn_number = max(self.turn_number, turn_num + 1)
        picker_name = KEVIN_NAME if picker == KEVIN_ID else self.opponent_name
        self.current_turn_player_id = next_turn or (self.opponent_id if picker == KEVIN_ID else KEVIN_ID)
        next_name = KEVIN_NAME if self.current_turn_player_id == KEVIN_ID else self.opponent_name

        kevin_lines = count_completed_lines(self.board, self.marked)
        if num > 0:
            print(f"🎲 [Turn {turn_num:02d}] {picker_name} picked #{num:02d} | Kevin Lines: {kevin_lines} | Next: {next_name}")
        else:
            print(f"⏱️ [Turn {turn_num:02d}] Turn passed by {picker_name} | Next: {next_name}")

        if kevin_lines >= 5:
            print(f"\n🎉 🎉 🎉 BINGO! KEVIN HAS 5 COMPLETED LINES! 🎉 🎉 🎉")
            self.send_chat("BINGO!! What a great match! GG! 👑🎉")
            self.publish(f"BINGO|{KEVIN_ID}|{kevin_lines}|{num}|{self.match_seed}")
            return

        self._check_and_trigger_turn()

    def _check_and_trigger_turn(self):
        if self.is_currently_away:
            return
        if self.current_turn_player_id == KEVIN_ID and self.in_game:
            threading.Thread(target=self._execute_kevin_pick, daemon=True).start()

    def _execute_kevin_pick(self):
        if self.is_currently_away:
            return

        # Check if exit should trigger on Kevin's turn at the 25s mark (strictly ONE time during match)
        if self.simulate_exit and not self.has_simulated_away and self.turn_number >= self.exit_turn:
            self.has_simulated_away = True
            print(f"\n=======================================================")
            print(f"  ⏱️ [KEVIN TURN {self.turn_number}] Turn started (Clock: 30s).")
            print(f"  Waiting 5 seconds so timer counts down to 25s before minimizing...")
            print(f"=======================================================")
            for s in [29, 28, 27, 26, 25]:
                if not self.in_game or not self.running or self.current_turn_player_id != KEVIN_ID:
                    return
                time.sleep(1.0)
                print(f"  ⏱️ [Clock] ~{s}s...")

            if not self.in_game or not self.running or self.current_turn_player_id != KEVIN_ID:
                return

            self.is_currently_away = True
            threading.Thread(target=self._perform_exit_simulation, daemon=True).start()
            return

        # Human-like thinking delay (1.5 to 2.2 seconds)
        think_delay = random.uniform(1.5, 2.2)
        time.sleep(think_delay)

        with self.lock:
            if not self.in_game or self.current_turn_player_id != KEVIN_ID or self.is_currently_away:
                return

            unmarked = [n for n in self.board if n not in self.picked_numbers]
            if not unmarked:
                return

            chosen_num = unmarked[0]

            self.picked_numbers.append(chosen_num)
            self.picked_by.append(KEVIN_ID)
            self.marked.add(chosen_num)

            curr_turn = self.turn_number
            next_turn_num = curr_turn + 1
            next_player = self.opponent_id
            self.turn_number = next_turn_num
            self.current_turn_player_id = next_player

            history_str = ",".join(str(x) for x in self.picked_numbers)
            picked_by_str = ",".join(self.picked_by)

            # Broadcast micro-packet
            micro = f"P|{chosen_num}|{KEVIN_ID}|{curr_turn}|{next_player}|{history_str}|{self.match_seed}|kevin_instance|{picked_by_str}"
            self.publish(micro)

            # Also broadcast JSON packet for full reliability
            json_pick = {
                "type": "PICK_NUMBER",
                "number": chosen_num,
                "playerId": KEVIN_ID,
                "turnNumber": curr_turn,
                "pickedHistory": self.picked_numbers,
                "pickedByHistory": self.picked_by,
                "currentTurnPlayerId": next_player,
                "seed": self.match_seed,
                "matchId": str(self.match_seed),
                "timestamp": int(time.time() * 1000)
            }
            self.publish(json.dumps(json_pick))

            kevin_lines = count_completed_lines(self.board, self.marked)
            print(f"👉 [Turn {curr_turn:02d}] Kevin picked #{chosen_num:02d} | Kevin Lines: {kevin_lines} | Next: {self.opponent_name}")

            # Friendly in-game reactions
            if kevin_lines == 3:
                self.send_chat("3 lines! I'm getting close! 🎯")
            elif kevin_lines == 4:
                self.send_chat("4 lines! Just one more for BINGO! ⚡")
            elif kevin_lines >= 5:
                print(f"\n🎉 🎉 🎉 BINGO! KEVIN HAS {kevin_lines} COMPLETED LINES! 🎉 🎉 🎉")
                self.send_chat("BINGO!! What an awesome match! GG! 👑🎉")
                self.publish(f"BINGO|{KEVIN_ID}|{kevin_lines}|{chosen_num}|{self.match_seed}")

    def _perform_exit_simulation(self):
        """Simulates mid-game minimize at 25s, stays away through next turn, then disconnects internet."""
        if self.exit_mode == "surrender":
            self.send_chat("Tapping Exit -> Surrender match now! 👋")
            time.sleep(0.5)
            surrender_pkt = {
                "type": "SURRENDER",
                "seed": self.match_seed,
                "matchId": str(self.match_seed),
                "playerId": KEVIN_ID,
                "displayName": KEVIN_NAME,
                "username": KEVIN_USERNAME,
                "isHost": True
            }
            self.publish(json.dumps(surrender_pkt))
            print("  ➜ Sent SURRENDER packet. You will see Opponent Surrendered.")
            time.sleep(0.5)
            self.stop()
            return

        print(f"\n=======================================================")
        print(f"  🚪 [SIMULATION] MINIMIZING AT 25s (Turn {self.turn_number})!")
        print(f"  Phase 1: Yellow ring 🟡, smooth 25s->0s countdown.")
        print(f"  Phase 2: Keep minimized for next turn (Turn 5 gets 5s skip).")
        print(f"  Phase 3: Disconnect internet completely (Red ring 🔴).")
        print(f"=======================================================")

        # Phase 1: Minimize at 25s
        self.send_chat("I minimized at 25s! Watch my timer run smoothly 25s->0s 🟡")
        away_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": KEVIN_ID,
            "displayName": KEVIN_NAME,
            "username": KEVIN_USERNAME,
            "readyStatus": "AWAY",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(away_pkt))
        print("  ➜ Sent PLAYER_PRESENCE (AWAY) at 25s mark! Kevin ring is now YELLOW.")
        print("  ➜ Turn 3 timer will smoothly run 25s -> 0s without dropping!")
        print("  ➜ Kevin will stay away (~38s) so you can observe the 5s skip on Turn 5!")

        # Phase 2: Stay away through Turn 3 (25s), Bob's Turn 4 (~8s), and Kevin's Turn 5 (5s skip)
        for sec in range(38, 0, -1):
            if not self.running:
                break
            if sec % 5 == 0 or sec <= 3:
                print(f"     [Away] Kevin is minimized... {sec}s remaining until internet disconnect")
            time.sleep(1.0)

        if not self.running:
            return

        # Phase 3: Disconnect internet!
        print(f"\n=======================================================")
        print(f"  🔌 [SIMULATION] DISCONNECTING KEVIN'S INTERNET NOW!")
        print(f"  Kevin ring will turn RED 🔴.")
        print(f"  Heartbeats stopped, MQTT disconnected.")
        print(f"  Game stays active for you to finish to BINGO!")
        print(f"=======================================================")
        self.send_chat("My internet is disconnecting now! Watch my ring turn Red 🔴")
        time.sleep(0.3)
        offline_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": KEVIN_ID,
            "displayName": KEVIN_NAME,
            "username": KEVIN_USERNAME,
            "readyStatus": "OFFLINE",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(offline_pkt))
        print("  ➜ Sent PLAYER_PRESENCE (OFFLINE)! Kevin ring is now RED.")
        time.sleep(0.5)

        # Cut network connection and stop all heartbeats
        self.heartbeats_enabled = False
        if self.client:
            self.client.loop_stop()
            self.client.disconnect()
        print("  ➜ Kevin's internet is completely DISCONNECTED.")
        print("  ➜ No more packets or heartbeats will be sent.")
        print("  ➜ You can finish your lines and claim BINGO!")

    def stop(self):
        self.running = False
        self.heartbeats_enabled = False
        if self.client:
            self.client.loop_stop()
            self.client.disconnect()

def main():
    import argparse
    parser = argparse.ArgumentParser(description="Play with Kevin (Virtual Bingo Companion)")
    parser.add_argument("--join", type=str, help="Join an existing room code created by the user")
    parser.add_argument("--room", type=str, help="Custom room code for Kevin to host")
    parser.add_argument("--exit-turn", type=int, default=5, help="Turn on which Kevin will simulate exit (default: 5)")
    parser.add_argument("--exit-mode", type=str, default="away", choices=["away", "offline", "surrender"],
                        help="Exit simulation mode: 'away' (Yellow ring, minimized app), 'offline' (Red ring), or 'surrender' (forfeit)")
    parser.add_argument("--no-exit", action="store_true", help="Disable exit simulation and play full match")
    args = parser.parse_args()

    simulate_exit = not args.no_exit

    if args.join:
        companion = KevinCompanion(
            room_code=args.join.strip().upper(),
            is_host=False,
            simulate_exit=simulate_exit,
            exit_turn=args.exit_turn,
            exit_mode=args.exit_mode
        )
    else:
        companion = KevinCompanion(
            room_code=args.room.strip().upper() if args.room else None,
            is_host=True,
            simulate_exit=simulate_exit,
            exit_turn=args.exit_turn,
            exit_mode=args.exit_mode
        )

    companion.start()

    try:
        while companion.running:
            time.sleep(1.0)
    except KeyboardInterrupt:
        print("\nStopping Kevin companion...")
        companion.stop()

if __name__ == "__main__":
    main()
