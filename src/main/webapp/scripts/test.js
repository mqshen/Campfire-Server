c.prototype.onPacket = function(a) {
            return this.socket.setHeartbeatTimeout(),
            a.type == "heartbeat" ? this.onHeartbeat() :
            (
            a.type == "connect" && a.endpoint == "" && this.onConnect(),
            a.type == "error" && a.advice == "reconnect" && (this.isOpen = !1),
            this.socket.onPacket(a),
            this)
        },