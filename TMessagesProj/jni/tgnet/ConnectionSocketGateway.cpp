/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * ConnectionSocket over the gateway: TCP to the gateway, TLS with system
 * roots and SNI, WebSocket upgrade, then the MTProto transport stream inside
 * binary WebSocket messages.
 */

#include <unistd.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <sys/epoll.h>
#include <string>
#include <vector>
#include "ByteStream.h"
#include "ConnectionSocket.h"
#include "ConnectionsManager.h"
#include "BuffersStorage.h"
#include "NativeByteBuffer.h"
#include "FileLog.h"
#include "Gateway.h"

#ifndef EPOLLRDHUP
#define EPOLLRDHUP 0x2000
#endif

void ConnectionSocket::openGatewayConnection(std::string address, uint16_t port, bool ipv6) {
    ConnectionsManager &manager = ConnectionsManager::getInstance(instanceNum);
    int64_t now = manager.getCurrentTimeMonotonicMillis();
    proxyAuthState = 0;
    tlsState = 0;
    if (gateway != nullptr) {
        delete gateway;
        gateway = nullptr;
    }
    GatewayConfig config = Gateway::getConfig();
    if (!config.valid()) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) gateway is not configured", this);
        Gateway::reportError(instanceNum, GatewayErrorNoToken, "no token");
        closeSocket(1, -1);
        return;
    }
    if (Gateway::isRejected(now)) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) gateway token was rejected recently", this);
        closeSocket(1, -1);
        return;
    }
    // The gateway accepts only an IPv4 DC address as a hint, for others it
    // picks the address by the DC id from the init bytes.
    std::string target;
    if (!ipv6 && !address.empty() && port != 0) {
        target = address + ":" + std::to_string(port);
    }
    gateway = new GatewaySession(config, target, now);
    if ((socketFd = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) can't create gateway socket", this);
        closeSocket(1, -1);
        return;
    }
    socketAddress.sin_family = AF_INET;
    socketAddress.sin_port = htons(config.port);
    if (LOGS_ENABLED) DEBUG_D("connection(%p) connecting via gateway, dc address %s", this, target.empty() ? "-" : target.c_str());
    if (inet_pton(AF_INET, config.host.c_str(), &socketAddress.sin_addr.s_addr) != 1) {
        waitingForHostResolve = config.host;
        manager.delegate->getHostByName(config.host, instanceNum, this);
        return;
    }
    openConnectionInternal(false);
}

void ConnectionSocket::gatewayFailed(int32_t error) {
    if (gateway != nullptr && gateway->errorCode != 0) {
        int32_t code = gateway->errorCode;
        if (gateway->state == GatewayStateClosed || gateway->state == GatewayStateOpen) {
            gateway->flush();
        }
        if (code == GatewayErrorTokenRevoked || (code == GatewayErrorRejected && gateway->errorReason == "http 404")) {
            Gateway::markRejected(ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis());
        }
        Gateway::reportError(instanceNum, code, gateway->errorReason);
    }
    closeSocket(1, error);
}

void ConnectionSocket::onGatewayEvent(uint32_t events) {
    if (events & (EPOLLIN | EPOLLOUT | EPOLLERR)) {
        int32_t error;
        if (checkSocketError(&error) != 0) {
            closeSocket(1, error);
            return;
        }
    }
    if (gateway->state == GatewayStateTcpConnecting) {
        if ((events & EPOLLOUT) == 0) {
            if (events & (EPOLLHUP | EPOLLRDHUP)) {
                closeSocket(1, -1);
            }
            return;
        }
        lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
        if (!gateway->startTls(socketFd)) {
            gatewayFailed(-1);
            return;
        }
    }
    if (gateway->state == GatewayStateTls) {
        if (gateway->handshake() != GatewaySession::StepOk) {
            gatewayFailed(-1);
            return;
        }
    }
    if (!gatewayPump()) {
        return;
    }
    if (events & (EPOLLHUP | EPOLLRDHUP)) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) gateway socket hang up", this);
        closeSocket(1, -1);
        return;
    }
    adjustWriteOp();
}

bool ConnectionSocket::gatewayPump() {
    if (gateway->state < GatewayStateUpgrading) {
        return true;
    }
    ConnectionsManager &manager = ConnectionsManager::getInstance(instanceNum);
    std::vector<uint8_t> payload;
    bool opened = false;
    GatewaySession::StepResult result = gateway->read(payload, opened);
    if (opened) {
        int64_t now = manager.getCurrentTimeMonotonicMillis();
        int32_t elapsed = (int32_t) (now - gateway->startMillis);
        Gateway::lastConnectMillis = elapsed;
        Gateway::openConnections++;
        if (LOGS_ENABLED) DEBUG_D("connection(%p) gateway WebSocket opened in %d ms", this, elapsed);
        lastEventTime = now;
        onConnectedSent = true;
        onConnected();
        if (gateway == nullptr) {
            return false;
        }
    }
    if (!payload.empty()) {
        lastEventTime = manager.getCurrentTimeMonotonicMillis();
        if (manager.delegate != nullptr) {
            manager.delegate->onBytesReceived((int32_t) payload.size(), currentNetworkType, instanceNum);
        }
        NativeByteBuffer *buffer = BuffersStorage::getInstance().getFreeBuffer((uint32_t) payload.size());
        buffer->writeBytes(payload.data(), (uint32_t) payload.size());
        buffer->rewind();
        onReceivedData(buffer);
        buffer->reuse();
        if (gateway == nullptr) {
            return false;
        }
    }
    if (result == GatewaySession::StepClosedNormally) {
        gateway->flush();
        closeSocket(1, 0);
        return false;
    } else if (result == GatewaySession::StepError) {
        gatewayFailed(-1);
        return false;
    }
    while (true) {
        if (gateway->state == GatewayStateOpen && !gateway->hasPendingWrite() && outgoingByteStream->hasData()) {
            NativeByteBuffer *buffer = manager.networkBuffer;
            buffer->clear();
            buffer->limit(256 * 1024);
            outgoingByteStream->get(buffer);
            buffer->flip();
            uint32_t remaining = buffer->remaining();
            if (remaining == 0) {
                break;
            }
            gateway->queueBinary(buffer->bytes(), remaining);
            outgoingByteStream->discard(remaining);
            if (manager.delegate != nullptr) {
                manager.delegate->onBytesSent((int32_t) remaining, currentNetworkType, instanceNum);
            }
        }
        if (!gateway->hasPendingWrite()) {
            break;
        }
        if (gateway->flush() != GatewaySession::StepOk) {
            gatewayFailed(-1);
            return false;
        }
        if (gateway->hasPendingWrite()) {
            break;
        }
    }
    return true;
}
