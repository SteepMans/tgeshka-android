/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * Gateway transport: every connection to a Telegram DC is a separate
 * WebSocket over TLS to the gateway (protocol v1). Inside the WebSocket go
 * exactly the bytes tgnet would send to the DC over TCP: the 64 byte
 * obfuscated2 init and the encrypted abridged stream.
 */

#ifndef TGNET_GATEWAY_H
#define TGNET_GATEWAY_H

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

typedef struct ssl_st SSL;

// Close codes of the gateway protocol and own error codes reported to Java.
enum GatewayErrorCode {
    GatewayErrorNoToken = 1,
    GatewayErrorRejected = 2,
    GatewayErrorTls = 3,
    GatewayErrorResolve = 4,
    GatewayErrorProtocol = 5,
    GatewayErrorTokenRevoked = 4401,
    GatewayErrorLimit = 4429,
    GatewayErrorLegsDown = 4502,
    GatewayErrorDcUnreachable = 4504,
};

struct GatewayConfig {
    std::string host;
    uint16_t port = 443;
    std::string path = "/v1/stream";
    std::string token;
    std::string client;

    bool valid() const {
        return !host.empty() && port != 0 && !token.empty();
    }
};

class Gateway {

public:
    static void setConfig(GatewayConfig config);
    static GatewayConfig getConfig();

    // After a rejected token new connections fail fast for a while instead of
    // hammering the gateway. Cleared by setConfig().
    static bool isRejected(int64_t nowMillis);
    static void markRejected(int64_t nowMillis);

    static void reportError(int32_t instanceNum, int32_t code, const std::string &reason);

    static std::atomic<int32_t> openConnections;
    static std::atomic<int64_t> bytesSent;
    static std::atomic<int64_t> bytesReceived;
    static std::atomic<int32_t> lastConnectMillis;
    static std::atomic<int32_t> lastErrorCode;

private:
    static std::mutex mutex;
    static GatewayConfig config;
    static int64_t rejectedUntil;
};

enum GatewayState {
    GatewayStateTcpConnecting = 0,
    GatewayStateTls = 1,
    GatewayStateUpgrading = 2,
    GatewayStateOpen = 3,
    GatewayStateClosed = 4,
};

// Per connection state, owned by ConnectionSocket.
class GatewaySession {

public:
    GatewaySession(GatewayConfig config, std::string target, int64_t startMillis);
    ~GatewaySession();

    // Returns false when TLS can't be set up.
    bool startTls(int fd);

    // Result of a step: keep going, the socket must be closed, or the
    // connection was closed normally by the gateway.
    enum StepResult {
        StepOk = 0,
        StepError = 1,
        StepClosedNormally = 2,
    };

    StepResult handshake();
    // Reads everything available from TLS. Payloads of binary messages are
    // appended to `payload`. Sets `opened` when the upgrade response arrived.
    StepResult read(std::vector<uint8_t> &payload, bool &opened);
    // Writes pending bytes. Returns StepOk also when the socket is not writable.
    StepResult flush();
    void queueBinary(const uint8_t *data, uint32_t size);
    void queueClose(uint16_t code);
    bool hasPendingWrite() const;

    GatewayState state = GatewayStateTcpConnecting;
    bool wantWrite = false;
    int32_t errorCode = 0;
    std::string errorReason;
    int64_t startMillis;

private:
    GatewayConfig config;
    std::string target;
    std::string key;
    SSL *ssl = nullptr;
    std::vector<uint8_t> out;
    size_t outOffset = 0;
    std::vector<uint8_t> in;

    void queueFrame(uint8_t opcode, const uint8_t *data, size_t size);
    void queueUpgradeRequest();
    StepResult parseUpgradeResponse(bool &opened);
    StepResult parseFrames(std::vector<uint8_t> &payload);
    StepResult fail(int32_t code, const std::string &reason);
};

#endif
