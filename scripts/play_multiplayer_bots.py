#!/usr/bin/env python3
"""
Multiplayer Bingo Bot Companion - 5-Player Real-World Simulation
Simulates 4 autonomous bot companions (Kevin, Stuart, Otto, Phil) playing
alongside the real user (Bob) with dynamic, realistic behaviors:
1. Random Minimizing (AWAY - Yellow ring 🟡) with brief absence, then returning online.
2. Random Network Glitch / Disconnecting (OFFLINE - Red ring 🔴) with brief absence, then reconnecting.
3. Mid-game Surrender: Around Turn 12-14, Phil surrenders due to "low battery", leaving 4 players to finish.
4. Human-like pick delays (1.5 - 2.2s), in-game chat banter, and BINGO detection.
"""

import sys
import os
import time
import json
import random
import threading
import ssl
import gzip
import base64
import urllib.request
import urllib.parse
from typing import Dict, List, Optional, Set
import paho.mqtt.client as mqtt

def encode_base64_url(raw: str) -> str:
    compressed = gzip.compress(raw.encode('utf-8'))
    b64 = base64.b64encode(compressed).decode('ascii')
    return f"GZ:{b64}"

# --- Configuration ---
BROKER_HOST = "p0812f88.ala.asia-southeast1.emqxsl.com"
BROKER_PORT = 8883
BROKER_USER = "Bora"
BROKER_PASS = "bora7989"

KEYVALUE_API_URL = "https://keyvalue.immanuel.co/api/KeyVal"
KEYVALUE_APP_KEY = "01c1ad298a0c4f8287754f910ea0aa4b"

# 4 Bot Companions
BOT_KEVIN = {"id": "player_kevin", "username": "kevin", "displayName": "Kevin", "isHost": True}
BOT_STUART = {"id": "player_stuart", "username": "stuart", "displayName": "Stuart", "isHost": False}
BOT_OTTO = {"id": "player_otto", "username": "otto", "displayName": "Otto", "isHost": False}
BOT_PHIL = {"id": "player_phil", "username": "phil", "displayName": "Phil", "isHost": False}

BOTS = [BOT_KEVIN, BOT_STUART, BOT_OTTO, BOT_PHIL]

# --- Deterministic Java Random & Shuffler ---
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
    # Diagonals
    if all(board[i * 5 + i] in marked for i in range(5)):
        lines += 1
    if all(board[i * 5 + (4 - i)] in marked for i in range(5)):
        lines += 1
    return lines

class MultiplayerBotCompanion:
    def __init__(self, room_code: Optional[str] = None, mode: str = "normal"):
        self.room_code = (room_code or "KV5000").upper()
        self.mode = mode.lower()
        self.simulation_enabled = (self.mode == "simulation")
        self.topic = f"bingo/v3/room/{self.room_code}"
        self.running = True
        self.lock = threading.RLock()

        # Network
        self.client_id = f"sim_multi_{random.randint(1000, 9999)}"
        self.client = mqtt.Client(client_id=self.client_id, protocol=mqtt.MQTTv311)
        self.client.username_pw_set(BROKER_USER, BROKER_PASS)
        self.client.tls_set(cert_reqs=ssl.CERT_NONE)
        self.client.tls_insecure_set(True)

        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.on_disconnect = self._on_disconnect

        # Match State
        self.in_game = False
        self.match_started = False
        self.match_seed = 0
        self.board_size = 5
        self.turn_number = 1
        self.scheduled_turn = 0
        self.current_turn_player_id = ""
        self.turn_order: List[str] = []
        self.picked_numbers: List[int] = []
        self.picked_by: List[str] = []
        self.pick_turns: List[int] = []
        self.marked: Set[int] = set()
        self.turn_start_time = time.time()
        self.turn_timeout_seconds = 30.0
        self.is_game_over = False
        self.winner_player_id = ""
        self.win_reason = ""

        # Opponent (Bob / User)
        self.opponent_id = ""
        self.opponent_name = "Opponent"
        self.opponent_ready = False

        # Bots Boards & States
        self.bot_boards: Dict[str, List[int]] = {}
        # Bot status: "ONLINE", "AWAY", "OFFLINE", "SURRENDERED"
        self.bot_status: Dict[str, str] = {b["id"]: "ONLINE" for b in BOTS}
        self.surrendered_player_ids: Set[str] = set()

        # Real-World Simulation Flags
        self.phil_has_surrendered = False
        self.active_simulation_bot: Optional[str] = None # At most 1 bot in transient state at a time

    def start(self):
        print("\n" + "="*65)
        print("  🤖 MULTIPLAYER BINGO COMPANION - 5 PLAYERS")
        print("="*65)
        print(f"Room Code:   {self.room_code}")
        print(f"Topic:       {self.topic}")
        print(f"Host:        Kevin 👑")
        print(f"Companions:  Stuart, Otto, Phil")
        print("="*65 + "\n")

        print("[Network] Connecting to MQTT broker...")
        self.client.connect(BROKER_HOST, BROKER_PORT, 60)
        self.client.loop_start()

        # Register room metadata for cloud discovery
        self._register_room_metadata()

        # Start background heartbeat loop
        threading.Thread(target=self._heartbeat_loop, daemon=True).start()

    def _register_room_metadata(self):
        """Registers room session with all 4 bots so app sees them in lobby."""
        players_meta = [
            {"id": b["id"], "displayName": b["displayName"], "username": b["username"], "isHost": b["isHost"], "lobbyReadyStatus": "READY"}
            for b in BOTS
        ]
        # Generous future heartbeat timestamp (+1 hr) prevents false room expiry from clock drift
        future_heartbeat = int(time.time() * 1000) + 3600000
        session = {
            "roomCode": self.room_code,
            "hostId": BOT_KEVIN["id"],
            "hostUsername": BOT_KEVIN["username"],
            "hostDisplayName": BOT_KEVIN["displayName"],
            "status": "WAITING",
            "boardSize": 5,
            "createdAt": int(time.time() * 1000),
            "lastHeartbeat": future_heartbeat,
            "players": players_meta,
            "currentSeed": 0,
            "isManualBoard": False,
            "isDynamicBoard": False
        }
        json_str = json.dumps(session)
        meta_topic = f"bingo/v3/room_meta/{self.room_code}"
        self.client.publish(meta_topic, json_str, qos=1, retain=True)

        try:
            gz_val = encode_base64_url(json_str)
            url = f"{KEYVALUE_API_URL}/UpdateValue/{KEYVALUE_APP_KEY}/room_{self.room_code}?value={urllib.parse.quote(gz_val)}"
            req = urllib.request.Request(url, data=b"", headers={"Content-Length": "0"}, method="POST")
            with urllib.request.urlopen(req, timeout=3.0) as resp:
                pass
        except Exception:
            pass

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        if rc == 0:
            print("[Network] Connected to broker successfully!")
            client.subscribe(self.topic, qos=1)
            print(f"[Network] Subscribed to room topic: {self.topic}")
            time.sleep(0.3)
            self._send_lobby_presences()
        else:
            print(f"[Network] Connection failed with rc={rc}")

    def _on_disconnect(self, client, userdata, *args, **kwargs):
        if self.running:
            print("[Network] Disconnected from broker. Auto-reconnecting...")

    def publish(self, payload: str):
        if self.client and self.running:
            self.client.publish(self.topic, payload, qos=1)

    def send_chat(self, text: str, sender: dict = BOT_KEVIN):
        pkt = {
            "type": "CHAT_MESSAGE",
            "playerId": sender["id"],
            "displayName": text,
            "username": sender["displayName"],
            "seed": self.match_seed,
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(pkt))
        print(f"💬 [{sender['displayName']}] \"{text}\"")

    def _send_lobby_presences(self):
        now_ms = int(time.time() * 1000)
        for i, b in enumerate(BOTS):
            is_host_flag = 1 if b["isHost"] else 0
            hb = f"H|{b['id']}|{b['displayName']}|{is_host_flag}|{now_ms}|{b['username']}||READY|{i+1}|5"
            self.publish(hb)
            ready_micro = f"R|{b['id']}|READY|{b['username']}|{b['displayName']}|{is_host_flag}|sim_bot|1|{i+1}|5"
            self.publish(ready_micro)

    def _heartbeat_loop(self):
        tick = 0
        while self.running:
            try:
                time.sleep(2.5)
                tick += 1
                if not self.in_game:
                    self._send_lobby_presences()
                    if tick % 4 == 0:
                        self._register_room_metadata()
                else:
                    for b in BOTS:
                        status = self.bot_status.get(b["id"], "ONLINE")
                        if status == "ONLINE":
                            now_ms = int(time.time() * 1000)
                            mhb = f"MHB|1|{self.match_seed}|ACTIVE|{b['id']}|{self.opponent_id}|sim_bot|{self.turn_number}|none|{now_ms}"
                            self.publish(mhb)
                    # Host Kevin periodically broadcasts GAME_SYNC so guest is always reconciled
                    if tick % 2 == 0:
                        self.send_game_sync()
            except Exception:
                pass

    def _get_turn_timeout_seconds(self, player_id: str) -> float:
        if not player_id:
            return 30.0
        if player_id.startswith("player_"):
            # Bot companion: 5.0s if away/offline before their turn starts, 30.0s if online
            return 5.0 if self.bot_status.get(player_id) != "ONLINE" else 30.0
        else:
            # Real player (Bob): ALWAYS give full 30.0 seconds!
            return 30.0

    def _finalize_game(self, winner_id: str, win_reason: str):
        with self.lock:
            if not self.in_game or self.is_game_over:
                return
            self.is_game_over = True
            self.in_game = False
            self.winner_player_id = winner_id
            self.win_reason = win_reason
            winner_name = self._get_player_name(winner_id)

            print("\n" + "★"*65)
            print(f"🏆 🏆 🏆  GAME OVER! WINNER: {winner_name.upper()}  🏆 🏆 🏆")
            print(f"Reason: {win_reason}")
            print("★"*65 + "\n")

            now_ms = int(time.time() * 1000)

            # 1. Broadcast GAME_OVER packet
            game_over_pkt = {
                "type": "GAME_OVER",
                "playerId": BOT_KEVIN["id"],
                "senderPlayerId": BOT_KEVIN["id"],
                "isHost": True,
                "winnerPlayerId": winner_id,
                "winReason": win_reason,
                "seed": self.match_seed,
                "matchId": str(self.match_seed),
                "turnNumber": max(self.turn_number, len(self.picked_numbers) + 1),
                "pickedHistory": list(self.picked_numbers),
                "pickedByHistory": list(self.picked_by),
                "isMatchFinished": True,
                "timestamp": now_ms
            }
            self.publish(json.dumps(game_over_pkt))

            # 2. Broadcast authoritative GAME_SYNC confirming match finalized
            self.send_game_sync()

            # 3. Chat celebration
            if winner_id == self.opponent_id:
                self.send_chat(f"CONGRATULATIONS {self.opponent_name.upper()}!! You won! What an incredible game! 👑🏆🎉", BOT_KEVIN)
                self.send_chat("GG Bob! You played brilliantly! 🍌🎉", BOT_STUART)
            else:
                self.send_chat(f"BINGO!! {winner_name} won the game! GG everyone! 👑🎉", BOT_KEVIN)

    def send_game_sync(self):
        if not self.running:
            return
        with self.lock:
            now_ms = int(time.time() * 1000)
            elapsed = time.time() - self.turn_start_time
            rem_ms = int(max(0.0, self.turn_timeout_seconds - elapsed) * 1000)
            sync_turn = max(self.turn_number, len(self.picked_numbers) + 1) if self.is_game_over else self.turn_number
            sync_pkt = {
                "type": "GAME_SYNC",
                "playerId": BOT_KEVIN["id"],
                "isHost": True,
                "seed": self.match_seed,
                "matchId": str(self.match_seed),
                "turnNumber": sync_turn,
                "currentTurnPlayerId": self.current_turn_player_id,
                "pickedHistory": list(self.picked_numbers),
                "pickedByHistory": list(self.picked_by),
                "turnIds": list(self.pick_turns) if len(self.pick_turns) == len(self.picked_numbers) else list(range(1, len(self.picked_numbers) + 1)),
                "boardHash": 0,
                "isPaused": False,
                "pauseRemainingSeconds": 0,
                "pauseEpisode": 0,
                "pauseOwnerId": "",
                "remainingTurnMillis": rem_ms,
                "eliminatedPlayerIds": list(self.surrendered_player_ids),
                "surrenderedPlayerIds": list(self.surrendered_player_ids),
                "winnerPlayerId": self.winner_player_id,
                "winReason": self.win_reason,
                "isMatchFinished": self.is_game_over,
                "runnerPlayerIds": [],
                "timestamp": now_ms
            }
            self.publish(json.dumps(sync_pkt))

    def _on_message(self, client, userdata, msg):
        try:
            payload = msg.payload.decode("utf-8").strip()
            self._handle_room_message(payload)
        except Exception as e:
            print(f"[Error] on_message: {e}")

    def _handle_room_message(self, payload: str):
        # 1. Heartbeat / Ready from Bob (Micro format)
        if payload.startswith("H|") or payload.startswith("R|"):
            parts = payload.split("|")
            pid = parts[1] if len(parts) > 1 else ""
            if payload.startswith("H|"):
                name = parts[2] if len(parts) > 2 else "Bob"
                is_ready = any(p == "READY" for p in parts[3:])
            else: # R|
                name = parts[4] if len(parts) > 4 and parts[4] else "Bob"
                is_ready = any(p == "READY" for p in parts[1:])

            # If not a bot, it's the real user!
            if pid and not pid.startswith("player_"):
                if self.opponent_id != pid:
                    self.opponent_id = pid
                    self.opponent_name = name or "Bob"
                    print(f"👋 [LOBBY] Real player '{self.opponent_name}' ({pid}) joined!")
                    self.send_chat(f"Welcome {self.opponent_name}! We're all ready to play! 👑", BOT_KEVIN)
                    self.send_chat(f"Hey {self.opponent_name}! Let's do this! 🍌", BOT_STUART)

                if is_ready and not self.opponent_ready:
                    self.opponent_ready = True
                    print(f"✅ [LOBBY] {self.opponent_name} tapped READY!")
                    if not self.match_started:
                        threading.Thread(target=self._start_countdown, daemon=True).start()

        # 2. JSON Packets
        elif payload.startswith("{") and payload.endswith("}"):
            try:
                data = json.loads(payload)
                pkt_type = data.get("type", "")
                pid = data.get("playerId", "")
                sender_instance = data.get("senderInstanceId", "")

                # Filter out echoed packets sent by this bot script
                if sender_instance == "sim_bot" or (pid and pid.startswith("player_")):
                    return

                # Check for lobby join / ready in JSON
                if pid and not pid.startswith("player_") and not self.in_game:
                    name = data.get("displayName") or data.get("username") or "Bob"
                    if self.opponent_id != pid:
                        self.opponent_id = pid
                        self.opponent_name = name
                        print(f"👋 [LOBBY] Real player '{self.opponent_name}' ({pid}) joined!")
                        self.send_chat(f"Welcome {self.opponent_name}! We're all ready to play! 👑", BOT_KEVIN)
                        self.send_chat(f"Hey {self.opponent_name}! Let's do this! 🍌", BOT_STUART)

                    if (data.get("readyStatus") == "READY" or pkt_type in ("READY", "PLAYER_READY")) and not self.opponent_ready:
                        self.opponent_ready = True
                        print(f"✅ [LOBBY] {self.opponent_name} tapped READY!")
                        if not self.match_started:
                            threading.Thread(target=self._start_countdown, daemon=True).start()

                if pkt_type == "PICK_NUMBER":
                    num = data.get("number", 0)
                    picker = data.get("playerId", "")
                    turn_num = data.get("turnNumber", self.turn_number + 1)
                    next_turn = data.get("currentTurnPlayerId", "")
                    self._on_pick_received(num, picker, turn_num, next_turn)
                elif pkt_type == "TURN_TIMEOUT":
                    timed_out_id = data.get("playerId", "")
                    turn_num = data.get("turnNumber", self.turn_number + 1)
                    next_turn = data.get("currentTurnPlayerId", "")
                    self._on_timeout_received(timed_out_id, turn_num, next_turn)
                elif pkt_type == "SYNC_REQUEST":
                    print(f"🔄 [SYNC] Received SYNC_REQUEST from {self.opponent_name}. Sending GAME_SYNC state...")
                    self.send_game_sync()
                elif pkt_type == "SURRENDER":
                    surr_id = data.get("playerId", "")
                    if surr_id and not surr_id.startswith("player_"):
                        print(f"🚪 [GAME] Real player '{self.opponent_name}' surrendered.")
                        self.surrendered_player_ids.add(surr_id)
                elif pkt_type == "BINGO_CLAIMED":
                    matches_seed = (not data.get("seed") or data.get("seed") == self.match_seed)
                    if self.in_game and matches_seed:
                        self._finalize_game(self.opponent_id, f"{self.opponent_name} completed 5 lines (BINGO)!")
                elif pkt_type == "CHAT_MESSAGE":
                    sender = data.get("username", "Opponent")
                    text = data.get("displayName", "")
                    if not pid.startswith("player_") and text:
                        print(f"💬 [{sender}] \"{text}\"")
            except Exception:
                pass

        # 3. Micro Pick
        elif payload.startswith("P|"):
            parts = payload.split("|")
            num = int(parts[1]) if len(parts) > 1 and (parts[1].isdigit() or parts[1] == "-1") else 0
            picker = parts[2] if len(parts) > 2 else ""
            turn_num = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else (self.turn_number + 1)
            next_turn = parts[4] if len(parts) > 4 else ""
            sender_instance = parts[7] if len(parts) > 7 else ""
            if not picker.startswith("player_") and sender_instance != "sim_bot":
                self._on_pick_received(num, picker, turn_num, next_turn)

        # 4. Micro Timeout Pass
        elif payload.startswith("T|"):
            parts = payload.split("|")
            timed_out_id = parts[1] if len(parts) > 1 else ""
            turn_num = int(parts[2]) if len(parts) > 2 and parts[2].isdigit() else (self.turn_number + 1)
            next_turn = parts[3] if len(parts) > 3 else ""
            sender_instance = parts[6] if len(parts) > 6 else ""
            if not timed_out_id.startswith("player_") and sender_instance != "sim_bot":
                self._on_timeout_received(timed_out_id, turn_num, next_turn)

        # 5. Micro Bingo Claim
        elif payload.startswith("BINGO|"):
            parts = payload.split("|")
            pid = parts[1] if len(parts) > 1 else ""
            seed_str = parts[4] if len(parts) > 4 else ""
            matches_seed = (not seed_str or seed_str == str(self.match_seed))
            if self.in_game and pid and not pid.startswith("player_") and matches_seed:
                self._finalize_game(self.opponent_id, f"{self.opponent_name} completed 5 lines (BINGO)!")

    def _start_countdown(self):
        self.match_started = True
        seed = int(time.time() * 1000) % 100000000
        all_players = [
            {"id": BOT_KEVIN["id"], "displayName": BOT_KEVIN["displayName"], "username": BOT_KEVIN["username"], "isHost": True},
            {"id": BOT_STUART["id"], "displayName": BOT_STUART["displayName"], "username": BOT_STUART["username"], "isHost": False},
            {"id": BOT_OTTO["id"], "displayName": BOT_OTTO["displayName"], "username": BOT_OTTO["username"], "isHost": False},
            {"id": BOT_PHIL["id"], "displayName": BOT_PHIL["displayName"], "username": BOT_PHIL["username"], "isHost": False},
            {"id": self.opponent_id, "displayName": self.opponent_name, "username": self.opponent_name.lower(), "isHost": False}
        ]

        # Deterministic turn order
        pids = [p["id"] for p in all_players]
        shuffled_pids = java_shuffle(sorted(pids), seed)
        first_turn = shuffled_pids[0]

        print(f"\n🚀 [HOST] All 5 players READY! Starting 3-second synchronized countdown...")
        self.send_chat("All ready! Game begins in 3... 2... 1... 🚀", BOT_KEVIN)

        start_pkt = {
            "type": "START_GAME",
            "boardSize": 5,
            "seed": seed,
            "matchId": str(seed),
            "protocolVersion": 0, # Multi-player protocol
            "playerId": BOT_KEVIN["id"],
            "currentTurnPlayerId": first_turn,
            "isManualBoard": False,
            "isDynamicBoard": False,
            "players": all_players
        }
        json_start = json.dumps(start_pkt)
        self.publish(json_start)
        time.sleep(0.15)
        self.publish(json_start)

        for c in [3, 2, 1]:
            print(f"  ⏱️  Countdown: {c}...")
            time.sleep(1.0)
        print("  🚀 5-PLAYER GAME BEGINS NOW!")

        # Initial chat message announcing host
        time.sleep(0.5)
        self.send_chat("👑 Kevin is the host", BOT_KEVIN)

        self._start_game(seed, all_players, shuffled_pids, first_turn)

    def _start_game(self, seed: int, all_players: list, turn_order: list, first_turn: str):
        if self.in_game:
            return
        self.in_game = True
        self.match_seed = seed
        self.turn_number = 1
        self.scheduled_turn = 0
        self.turn_order = turn_order
        self.current_turn_player_id = first_turn
        self.picked_numbers.clear()
        self.picked_by.clear()
        self.pick_turns.clear()
        self.marked.clear()

        # Generate distinct boards for each bot
        base = list(range(1, 26))
        for i, b in enumerate(BOTS):
            self.bot_boards[b["id"]] = java_shuffle(base, seed + (i + 1) * 31337)

        print("\n" + "="*65)
        print("🎮 🎮 🎮  5-PLAYER MATCH STARTED!  🎮 🎮 🎮")
        print(f"Room Code:       {self.room_code}")
        print(f"Match Seed:      {self.match_seed}")
        print(f"Turn Order:      {self.turn_order}")
        first_name = self._get_player_name(first_turn)
        print(f"First Turn:      {first_name}")
        print("="*65 + "\n")

        # Start dynamic simulation manager if simulation mode enabled
        if self.simulation_enabled:
            threading.Thread(target=self._dynamic_simulation_loop, daemon=True).start()
        else:
            print("✨ [NORMAL MODE] All bots will stay 100% ONLINE throughout the match.")

        # Start turn timer monitoring thread to prevent game from freezing at 0s
        self.turn_start_time = time.time()
        self.turn_timeout_seconds = 30.0
        threading.Thread(target=self._turn_timer_loop, daemon=True).start()

        self._check_and_trigger_turn()

    def _turn_timer_loop(self):
        """Monitors turn timeout and automatically passes turn if current player times out."""
        while self.running and self.in_game:
            time.sleep(0.5)
            if not self.in_game or not self.current_turn_player_id:
                continue
            if (time.time() - self.turn_start_time) >= self.turn_timeout_seconds:
                self._handle_turn_timeout()

    def _handle_turn_timeout(self):
        with self.lock:
            if not self.in_game or not self.current_turn_player_id:
                return
            timed_out_id = self.current_turn_player_id
            curr_turn = self.turn_number
            next_turn_num = curr_turn + 1
            next_player = self._get_next_player(timed_out_id)

            self.turn_number = next_turn_num
            self.current_turn_player_id = next_player
            self.turn_start_time = time.time()
            self.turn_timeout_seconds = self._get_turn_timeout_seconds(next_player)

            timed_out_name = self._get_player_name(timed_out_id)
            next_name = self._get_player_name(next_player)
            print(f"⏱️ [Turn {curr_turn:02d}] Turn TIMED OUT for {timed_out_name} ➔ Passed to {next_name} (Turn {next_turn_num:02d})")

            history_str = ",".join(str(x) for x in self.picked_numbers)
            picked_by_str = ",".join(self.picked_by)

            # Broadcast TURN_TIMEOUT micro-packet
            micro = f"T|{timed_out_id}|{next_turn_num}|{next_player}|{history_str}|{self.match_seed}|sim_bot|{picked_by_str}"
            self.publish(micro)

            # Broadcast TURN_TIMEOUT JSON packet
            json_timeout = {
                "type": "TURN_TIMEOUT",
                "playerId": timed_out_id,
                "senderPlayerId": BOT_KEVIN["id"],
                "turnNumber": next_turn_num,
                "closedTurnNumber": curr_turn,
                "currentTurnPlayerId": next_player,
                "seed": self.match_seed,
                "matchId": str(self.match_seed),
                "timestamp": int(time.time() * 1000)
            }
            self.publish(json.dumps(json_timeout))

        self._check_and_trigger_turn()

    def _get_player_name(self, pid: str) -> str:
        for b in BOTS:
            if b["id"] == pid:
                return b["displayName"]
        if pid == self.opponent_id:
            return self.opponent_name
        return pid

    def _get_next_player(self, current_pid: str) -> str:
        active = [p for p in self.turn_order if p not in self.surrendered_player_ids]
        if not active:
            return ""
        if current_pid in active:
            idx = active.index(current_pid)
            return active[(idx + 1) % len(active)]
        return active[0]

    def _on_pick_received(self, num: int, picker: str, turn_num: int, next_turn: str):
        with self.lock:
            if not self.in_game or self.is_game_over:
                return

            picker_name = self._get_player_name(picker)

            if num > 0 and num not in self.marked:
                self.picked_numbers.append(num)
                self.picked_by.append(picker)
                self.pick_turns.append(self.turn_number)
                self.marked.add(num)
                print(f"🎲 [Turn {self.turn_number:02d}] {picker_name} picked #{num:02d}")
            elif num <= 0:
                print(f"⏱️ [Turn {self.turn_number:02d}] Turn skipped/passed by {picker_name}")
            else:
                # Duplicate pick packet for already marked number
                return

            self.turn_number = max(self.turn_number + 1, turn_num)

            # Calculate next turn
            calculated_next = next_turn or self._get_next_player(picker)
            self.current_turn_player_id = calculated_next
            self.turn_start_time = time.time()
            self.turn_timeout_seconds = self._get_turn_timeout_seconds(calculated_next)

            next_name = self._get_player_name(self.current_turn_player_id)
            print(f"   ➔ Next Turn: {next_name} (Turn {self.turn_number:02d})")

            # Check line completions for bots
            for b in BOTS:
                if b["id"] not in self.surrendered_player_ids:
                    lines = count_completed_lines(self.bot_boards[b["id"]], self.marked)
                    if lines >= 5:
                        self._finalize_game(b["id"], f"{b['displayName']} completed 5 lines (BINGO)!")
                        return

        self._check_and_trigger_turn()

    def _on_timeout_received(self, timed_out_id: str, turn_num: int, next_turn: str):
        with self.lock:
            if not self.in_game or self.is_game_over:
                return
            timed_out_name = self._get_player_name(timed_out_id)
            print(f"⏱️ [Turn {self.turn_number:02d}] Turn TIMED OUT / Passed by {timed_out_name}")

            self.turn_number = max(self.turn_number + 1, turn_num)
            calculated_next = next_turn or self._get_next_player(timed_out_id)
            self.current_turn_player_id = calculated_next
            self.turn_start_time = time.time()
            self.turn_timeout_seconds = self._get_turn_timeout_seconds(calculated_next)

            next_name = self._get_player_name(self.current_turn_player_id)
            print(f"   ➔ Next Turn: {next_name} (Turn {self.turn_number:02d})")

        self._check_and_trigger_turn()

    def _check_and_trigger_turn(self):
        with self.lock:
            curr_id = self.current_turn_player_id
            if not self.in_game or self.is_game_over or not curr_id:
                return
            curr_turn = self.turn_number

            # If already scheduled for this turn number, do not schedule again
            if self.scheduled_turn == curr_turn:
                return

            # Reset turn start time and timer length
            self.turn_start_time = time.time()
            self.turn_timeout_seconds = self._get_turn_timeout_seconds(curr_id)

            # If it's a bot's turn:
            if curr_id.startswith("player_"):
                # Check if bot is currently away or offline
                status = self.bot_status.get(curr_id, "ONLINE")
                if status != "ONLINE":
                    print(f"   ⏳ [{self._get_player_name(curr_id)}] is currently {status}. Will wait {self.turn_timeout_seconds:.1f}s for turn skip.")
                    return

                self.scheduled_turn = curr_turn
                threading.Thread(target=self._execute_bot_pick, args=(curr_id, curr_turn), daemon=True).start()

    def _execute_bot_pick(self, bot_id: str, turn_at_schedule: int):
        bot = next((b for b in BOTS if b["id"] == bot_id), None)
        if not bot:
            return

        # Fast think delay (0.8 - 1.2 seconds for rapid testing)
        think_delay = random.uniform(0.8, 1.2)
        time.sleep(think_delay)

        with self.lock:
            if not self.in_game or self.is_game_over or self.current_turn_player_id != bot_id:
                return
            if self.turn_number != turn_at_schedule:
                return
            if self.bot_status.get(bot_id) != "ONLINE":
                return

            board = self.bot_boards[bot_id]
            unmarked = [n for n in board if n not in self.picked_numbers]
            if not unmarked:
                return

            curr_turn = self.turn_number
            chosen_num = unmarked[0]
            self.picked_numbers.append(chosen_num)
            self.picked_by.append(bot_id)
            self.pick_turns.append(curr_turn)
            self.marked.add(chosen_num)

            next_turn_num = curr_turn + 1
            next_player = self._get_next_player(bot_id)

            self.turn_number = next_turn_num
            self.current_turn_player_id = next_player
            self.turn_start_time = time.time()
            self.turn_timeout_seconds = self._get_turn_timeout_seconds(next_player)

            history_str = ",".join(str(x) for x in self.picked_numbers)
            picked_by_str = ",".join(self.picked_by)

            # Broadcast micro-packet with next_turn_num (curr_turn + 1)
            micro = f"P|{chosen_num}|{bot_id}|{next_turn_num}|{next_player}|{history_str}|{self.match_seed}|sim_bot|{picked_by_str}"
            self.publish(micro)

            # Broadcast JSON packet with next_turn_num and closedTurnNumber
            json_pick = {
                "type": "PICK_NUMBER",
                "number": chosen_num,
                "playerId": bot_id,
                "senderPlayerId": bot_id,
                "senderInstanceId": "sim_bot",
                "turnNumber": next_turn_num,
                "closedTurnNumber": curr_turn,
                "pickedHistory": list(self.picked_numbers),
                "pickedByHistory": list(self.picked_by),
                "currentTurnPlayerId": next_player,
                "seed": self.match_seed,
                "matchId": str(self.match_seed),
                "timestamp": int(time.time() * 1000)
            }
            self.publish(json.dumps(json_pick))

            lines = count_completed_lines(board, self.marked)
            next_name = self._get_player_name(next_player)
            print(f"👉 [Turn {curr_turn:02d}] {bot['displayName']} picked #{chosen_num:02d} | Lines: {lines} | Next: {next_name}")

            # Friendly banter
            if lines == 3:
                self.send_chat("3 lines! Making progress! 🎯", bot)
            elif lines == 4:
                self.send_chat("4 lines! Just one more for BINGO! ⚡", bot)
            elif lines >= 5:
                self._finalize_game(bot_id, f"{bot['displayName']} completed 5 lines (BINGO)!")
                return

        self._check_and_trigger_turn()

    # =========================================================================
    # DYNAMIC REAL-WORLD SIMULATION (Random Minimize, Disconnect, Surrender)
    # =========================================================================
    def _dynamic_simulation_loop(self):
        """Orchestrates random real-world events across the 4 bots."""
        time.sleep(10.0) # Let the match get underway first (Turns 1-4)

        while self.running and self.in_game:
            # Random interval between events: 12 to 20 seconds
            wait_time = random.uniform(12.0, 20.0)
            time.sleep(wait_time)

            if not self.in_game or not self.running:
                break

            # Check if it's time for Phil to surrender (Turn 12-16)
            if not self.phil_has_surrendered and self.turn_number >= 12:
                self._simulate_phil_surrender()
                continue

            # Pick a bot eligible for a transient event (must be ONLINE and not surrendered)
            eligible_bots = [
                b for b in BOTS 
                if self.bot_status[b["id"]] == "ONLINE" and b["id"] not in self.surrendered_player_ids
            ]
            if not eligible_bots or self.active_simulation_bot is not None:
                continue

            chosen_bot = random.choice(eligible_bots)
            event_type = random.choice(["AWAY", "OFFLINE"])

            if event_type == "AWAY":
                threading.Thread(target=self._simulate_transient_away, args=(chosen_bot,), daemon=True).start()
            else:
                threading.Thread(target=self._simulate_transient_offline, args=(chosen_bot,), daemon=True).start()

    def _simulate_transient_away(self, bot: dict):
        bot_id = bot["id"]
        bot_name = bot["displayName"]
        self.active_simulation_bot = bot_id
        self.bot_status[bot_id] = "AWAY"

        print(f"\n🟡 [SIMULATION] {bot_name} MINIMIZED the game! (Sending AWAY presence)")
        away_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": bot_id,
            "displayName": bot_name,
            "username": bot["username"],
            "readyStatus": "AWAY",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(away_pkt))

        # Stay away for 8 to 11 seconds
        away_duration = random.uniform(8.0, 11.0)
        time.sleep(away_duration)

        if not self.running or not self.in_game:
            return

        # Reconnect / Return online
        print(f"🟢 [SIMULATION] {bot_name} RETURNED! (Sending ONLINE presence)")
        online_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": bot_id,
            "displayName": bot_name,
            "username": bot["username"],
            "readyStatus": "ONLINE",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(online_pkt))
        self.bot_status[bot_id] = "ONLINE"
        self.active_simulation_bot = None

        return_chats = [
            f"Back! Phone call ended 📱🟢",
            f"I'm back! Let's continue 😄",
            f"Sorry, had to reply to a message! 🟢"
        ]
        self.send_chat(random.choice(return_chats), bot)
        self._check_and_trigger_turn()

    def _simulate_transient_offline(self, bot: dict):
        bot_id = bot["id"]
        bot_name = bot["displayName"]
        self.active_simulation_bot = bot_id
        self.bot_status[bot_id] = "OFFLINE"

        print(f"\n🔴 [SIMULATION] {bot_name} DISCONNECTED! (Sending OFFLINE presence)")
        offline_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": bot_id,
            "displayName": bot_name,
            "username": bot["username"],
            "readyStatus": "OFFLINE",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(offline_pkt))

        # Stay offline for 6 to 9 seconds
        disconnect_duration = random.uniform(6.0, 9.0)
        time.sleep(disconnect_duration)

        if not self.running or not self.in_game:
            return

        # Reconnect online
        print(f"🟢 [SIMULATION] {bot_name} RECONNECTED! (Sending ONLINE presence)")
        online_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": bot_id,
            "displayName": bot_name,
            "username": bot["username"],
            "readyStatus": "ONLINE",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(online_pkt))
        self.bot_status[bot_id] = "ONLINE"
        self.active_simulation_bot = None

        reconnect_chats = [
            f"Wi-Fi hiccup! Reconnected 📶🟢",
            f"Lost network for a second, I'm back! 🟢",
            f"Signal restored! 🚀"
        ]
        self.send_chat(random.choice(reconnect_chats), bot)
        self._check_and_trigger_turn()

    def _simulate_phil_surrender(self):
        """Simulates Phil surrendering around Turn 12-14 due to low battery."""
        self.phil_has_surrendered = True
        phil_id = BOT_PHIL["id"]
        self.surrendered_player_ids.add(phil_id)
        self.bot_status[phil_id] = "SURRENDERED"

        print(f"\n🚪 [SIMULATION] PHIL IS SURRENDERING THE MATCH! (Turn {self.turn_number})")
        self.send_chat("Battery is at 1%! Have to surrender, good luck everyone! 👋🔋", BOT_PHIL)
        time.sleep(0.5)

        surrender_pkt = {
            "type": "SURRENDER",
            "seed": self.match_seed,
            "matchId": str(self.match_seed),
            "playerId": phil_id,
            "displayName": BOT_PHIL["displayName"],
            "username": BOT_PHIL["username"],
            "isHost": False
        }
        self.publish(json.dumps(surrender_pkt))
        print("  ➜ Sent SURRENDER packet for Phil. Phil is now eliminated!")
        print("  ➜ Match continues seamlessly for Bob, Kevin, Stuart, and Otto!")

        # If it was Phil's turn, advance turn immediately
        if self.current_turn_player_id == phil_id:
            with self.lock:
                next_player = self._get_next_player(phil_id)
                self.current_turn_player_id = next_player
                self.turn_number += 1
                self.turn_start_time = time.time()
                self.turn_timeout_seconds = self._get_turn_timeout_seconds(next_player)
                print(f"  ➜ Advanced turn from surrendered Phil to {self._get_player_name(next_player)}!")
            self._check_and_trigger_turn()

    def stop(self):
        self.running = False
        if self.client:
            self.client.loop_stop()
            self.client.disconnect()

def main():
    import argparse
    parser = argparse.ArgumentParser(description="Multiplayer 5-Player Bingo Companion with Real-World Network Simulation")
    parser.add_argument("--room", type=str, default="KV5000", help="Room code for Kevin to host (default: KV5000)")
    parser.add_argument("--mode", type=str, choices=["normal", "simulation"], default="normal", help="Match mode: 'normal' (all bots online) or 'simulation' (random disconnects/surrender)")
    args = parser.parse_args()

    companion = MultiplayerBotCompanion(room_code=args.room, mode=args.mode)
    companion.start()

    try:
        while companion.running:
            time.sleep(1.0)
    except KeyboardInterrupt:
        print("\nStopping multiplayer bots...")
        companion.stop()

if __name__ == "__main__":
    main()
