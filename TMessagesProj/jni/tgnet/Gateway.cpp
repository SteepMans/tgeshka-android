/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

#include "Gateway.h"

#include <dirent.h>
#include <cstring>
#include <algorithm>
#include <openssl/base64.h>
#include <openssl/err.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include "ConnectionsManager.h"
#include "FileLog.h"

namespace {

constexpr size_t kMaxResponseHead = 16 * 1024;
constexpr uint64_t kMaxFramePayload = 16 * 1024 * 1024;
constexpr const char *kWebSocketGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
constexpr int64_t kRejectedPause = 30 * 1000;

constexpr uint8_t kOpContinuation = 0x0;
constexpr uint8_t kOpText = 0x1;
constexpr uint8_t kOpBinary = 0x2;
constexpr uint8_t kOpClose = 0x8;
constexpr uint8_t kOpPing = 0x9;
constexpr uint8_t kOpPong = 0xA;

constexpr uint16_t kCloseNormal = 1000;
constexpr uint16_t kCloseGoingAway = 1001;
constexpr uint16_t kCloseProtocolError = 1002;
constexpr uint16_t kCloseUnsupportedData = 1003;
constexpr uint16_t kCloseNoStatus = 1005;
constexpr uint16_t kCloseTooBig = 1009;

const char *kRootDirectories[] = {
    "/apex/com.android.conscrypt/cacerts",
    "/system/etc/security/cacerts",
};

int loadRoots(X509_STORE *store, const char *directory) {
    DIR *dir = opendir(directory);
    if (dir == nullptr) {
        return 0;
    }
    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        std::string path = std::string(directory) + "/" + entry->d_name;
        BIO *bio = BIO_new_file(path.c_str(), "r");
        if (bio == nullptr) {
            continue;
        }
        X509 *cert = PEM_read_bio_X509(bio, nullptr, nullptr, nullptr);
        if (cert != nullptr) {
            if (X509_STORE_add_cert(store, cert) == 1) {
                count++;
            }
            X509_free(cert);
        }
        BIO_free(bio);
    }
    closedir(dir);
    ERR_clear_error();
    return count;
}

SSL_CTX *sharedContext() {
    static std::once_flag once;
    static SSL_CTX *context = nullptr;
    std::call_once(once, [] {
        SSL_CTX *ctx = SSL_CTX_new(TLS_method());
        if (ctx == nullptr) {
            return;
        }
        SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
        SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, nullptr);
        static const uint8_t alpn[] = { 8, 'h', 't', 't', 'p', '/', '1', '.', '1' };
        SSL_CTX_set_alpn_protos(ctx, alpn, sizeof(alpn));
        X509_STORE *store = SSL_CTX_get_cert_store(ctx);
        int total = 0;
        for (const char *directory : kRootDirectories) {
            total = loadRoots(store, directory);
            if (total > 0) {
                break;
            }
        }
        if (LOGS_ENABLED) DEBUG_D("gateway: loaded %d system root certificates", total);
        if (total == 0) {
            SSL_CTX_free(ctx);
            return;
        }
        context = ctx;
    });
    return context;
}

std::string base64(const uint8_t *data, size_t size) {
    size_t length = 0;
    EVP_EncodedLength(&length, size);
    std::string result(length, '\0');
    size_t written = EVP_EncodeBlock((uint8_t *) &result[0], data, size);
    result.resize(written);
    return result;
}

std::string toLower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return (char) std::tolower(c);
    });
    return value;
}

std::string trim(const std::string &value) {
    size_t begin = value.find_first_not_of(" \t\r");
    if (begin == std::string::npos) {
        return "";
    }
    size_t end = value.find_last_not_of(" \t\r");
    return value.substr(begin, end - begin + 1);
}

}

std::mutex Gateway::mutex;
GatewayConfig Gateway::config;
int64_t Gateway::rejectedUntil = 0;
std::atomic<int32_t> Gateway::openConnections(0);
std::atomic<int64_t> Gateway::bytesSent(0);
std::atomic<int64_t> Gateway::bytesReceived(0);
std::atomic<int32_t> Gateway::lastConnectMillis(0);
std::atomic<int32_t> Gateway::lastErrorCode(0);

void Gateway::setConfig(GatewayConfig value) {
    std::lock_guard<std::mutex> lock(mutex);
    config = std::move(value);
    rejectedUntil = 0;
    lastErrorCode = 0;
}

GatewayConfig Gateway::getConfig() {
    std::lock_guard<std::mutex> lock(mutex);
    return config;
}

bool Gateway::isRejected(int64_t nowMillis) {
    std::lock_guard<std::mutex> lock(mutex);
    return rejectedUntil != 0 && nowMillis < rejectedUntil;
}

void Gateway::markRejected(int64_t nowMillis) {
    std::lock_guard<std::mutex> lock(mutex);
    rejectedUntil = nowMillis + kRejectedPause;
}

void Gateway::reportError(int32_t instanceNum, int32_t code, const std::string &reason) {
    lastErrorCode = code;
    if (LOGS_ENABLED) DEBUG_E("gateway: error %d (%s)", code, reason.c_str());
    ConnectionsManager &manager = ConnectionsManager::getInstance(instanceNum);
    if (manager.delegate != nullptr) {
        manager.delegate->onGatewayError(instanceNum, code, reason);
    }
}

GatewaySession::GatewaySession(GatewayConfig value, std::string hint, int64_t start) :
    startMillis(start), config(std::move(value)), target(std::move(hint)) {
}

GatewaySession::~GatewaySession() {
    if (ssl != nullptr) {
        SSL_free(ssl);
        ssl = nullptr;
    }
}

bool GatewaySession::startTls(int fd) {
    SSL_CTX *context = sharedContext();
    if (context == nullptr) {
        fail(GatewayErrorTls, "no root certificates");
        return false;
    }
    ssl = SSL_new(context);
    if (ssl == nullptr) {
        fail(GatewayErrorTls, "ssl_new");
        return false;
    }
    SSL_set_mode(ssl, SSL_MODE_ENABLE_PARTIAL_WRITE | SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER);
    SSL_set_tlsext_host_name(ssl, config.host.c_str());
    X509_VERIFY_PARAM *param = SSL_get0_param(ssl);
    X509_VERIFY_PARAM_set_hostflags(param, X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS);
    if (X509_VERIFY_PARAM_set1_host(param, config.host.c_str(), config.host.size()) != 1) {
        fail(GatewayErrorTls, "bad host");
        return false;
    }
    SSL_set_fd(ssl, fd);
    SSL_set_connect_state(ssl);
    state = GatewayStateTls;
    return true;
}

GatewaySession::StepResult GatewaySession::handshake() {
    wantWrite = false;
    int result = SSL_do_handshake(ssl);
    if (result == 1) {
        if (SSL_get_verify_result(ssl) != X509_V_OK) {
            return fail(GatewayErrorTls, "certificate");
        }
        if (LOGS_ENABLED) DEBUG_D("gateway: TLS established with %s", config.host.c_str());
        state = GatewayStateUpgrading;
        queueUpgradeRequest();
        return StepOk;
    }
    int error = SSL_get_error(ssl, result);
    if (error == SSL_ERROR_WANT_READ) {
        return StepOk;
    } else if (error == SSL_ERROR_WANT_WRITE) {
        wantWrite = true;
        return StepOk;
    }
    long verify = SSL_get_verify_result(ssl);
    char description[160];
    ERR_error_string_n(ERR_peek_last_error(), description, sizeof(description));
    if (LOGS_ENABLED) DEBUG_E("gateway: TLS handshake failed, ssl error %d, verify %ld, %s", error, verify, description);
    ERR_clear_error();
    return fail(GatewayErrorTls, verify != X509_V_OK ? "certificate" : "handshake");
}

void GatewaySession::queueUpgradeRequest() {
    uint8_t nonce[16];
    RAND_bytes(nonce, sizeof(nonce));
    key = base64(nonce, sizeof(nonce));

    std::string path = config.path.empty() ? "/" : config.path;
    if (!target.empty()) {
        path += (path.find('?') == std::string::npos ? "?a=" : "&a=") + target;
    }
    std::string host = config.host;
    if (config.port != 443) {
        host += ":" + std::to_string(config.port);
    }
    std::string request;
    request.reserve(512);
    request += "GET " + path + " HTTP/1.1\r\n";
    request += "Host: " + host + "\r\n";
    request += "Upgrade: websocket\r\n";
    request += "Connection: Upgrade\r\n";
    request += "Sec-WebSocket-Version: 13\r\n";
    request += "Sec-WebSocket-Key: " + key + "\r\n";
    request += "Authorization: Bearer " + config.token + "\r\n";
    if (!config.client.empty()) {
        request += "X-TGeshka-Client: " + config.client + "\r\n";
    }
    request += "\r\n";
    out.insert(out.end(), request.begin(), request.end());
}

GatewaySession::StepResult GatewaySession::read(std::vector<uint8_t> &payload, bool &opened) {
    opened = false;
    if (state != GatewayStateUpgrading && state != GatewayStateOpen) {
        return StepOk;
    }
    uint8_t buffer[16 * 1024];
    while (true) {
        int count = SSL_read(ssl, buffer, sizeof(buffer));
        if (count > 0) {
            Gateway::bytesReceived += count;
            in.insert(in.end(), buffer, buffer + count);
            continue;
        }
        int error = SSL_get_error(ssl, count);
        if (error == SSL_ERROR_WANT_READ) {
            break;
        } else if (error == SSL_ERROR_WANT_WRITE) {
            wantWrite = true;
            break;
        } else if (error == SSL_ERROR_ZERO_RETURN) {
            if (LOGS_ENABLED) DEBUG_D("gateway: TLS closed by peer");
            StepResult result = state == GatewayStateUpgrading ? parseUpgradeResponse(opened) : StepOk;
            if (result == StepOk && state == GatewayStateOpen) {
                result = parseFrames(payload);
            }
            return result == StepOk ? StepClosedNormally : result;
        }
        ERR_clear_error();
        if (LOGS_ENABLED) DEBUG_E("gateway: TLS read failed, ssl error %d", error);
        return StepError;
    }
    if (state == GatewayStateUpgrading) {
        StepResult result = parseUpgradeResponse(opened);
        if (result != StepOk || state != GatewayStateOpen) {
            return result;
        }
    }
    return parseFrames(payload);
}

GatewaySession::StepResult GatewaySession::parseUpgradeResponse(bool &opened) {
    static const char kEnd[] = "\r\n\r\n";
    auto end = std::search(in.begin(), in.end(), kEnd, kEnd + 4);
    if (end == in.end()) {
        if (in.size() > kMaxResponseHead) {
            return fail(GatewayErrorRejected, "bad response");
        }
        return StepOk;
    }
    std::string head(in.begin(), end);
    in.erase(in.begin(), end + 4);

    size_t lineEnd = head.find('\n');
    std::string status = trim(head.substr(0, lineEnd));
    int code = 0;
    size_t space = status.find(' ');
    if (space != std::string::npos) {
        code = atoi(status.c_str() + space + 1);
    }
    if (code != 101) {
        if (LOGS_ENABLED) DEBUG_E("gateway: upgrade refused with HTTP %d", code);
        return fail(GatewayErrorRejected, "http " + std::to_string(code));
    }
    std::string accept;
    size_t position = lineEnd == std::string::npos ? head.size() : lineEnd + 1;
    while (position < head.size()) {
        size_t next = head.find('\n', position);
        std::string line = head.substr(position, next == std::string::npos ? std::string::npos : next - position);
        position = next == std::string::npos ? head.size() : next + 1;
        size_t colon = line.find(':');
        if (colon != std::string::npos && toLower(trim(line.substr(0, colon))) == "sec-websocket-accept") {
            accept = trim(line.substr(colon + 1));
        }
    }
    std::string source = key + kWebSocketGuid;
    uint8_t digest[SHA_DIGEST_LENGTH];
    SHA1((const uint8_t *) source.data(), source.size(), digest);
    if (accept != base64(digest, sizeof(digest))) {
        return fail(GatewayErrorRejected, "bad accept");
    }
    state = GatewayStateOpen;
    opened = true;
    return StepOk;
}

GatewaySession::StepResult GatewaySession::parseFrames(std::vector<uint8_t> &payload) {
    size_t offset = 0;
    const uint8_t *data = in.data();
    StepResult result = StepOk;
    while (in.size() - offset >= 2) {
        uint8_t first = data[offset];
        uint8_t second = data[offset + 1];
        uint8_t opcode = (uint8_t) (first & 0x0F);
        bool masked = (second & 0x80) != 0;
        uint64_t length = second & 0x7F;
        size_t header = 2;
        if (length == 126) {
            if (in.size() - offset < 4) {
                break;
            }
            length = ((uint64_t) data[offset + 2] << 8) | data[offset + 3];
            header = 4;
        } else if (length == 127) {
            if (in.size() - offset < 10) {
                break;
            }
            length = 0;
            for (int a = 0; a < 8; a++) {
                length = (length << 8) | data[offset + 2 + a];
            }
            header = 10;
        }
        if (length > kMaxFramePayload) {
            queueClose(kCloseTooBig);
            result = fail(kCloseTooBig, "frame too big");
            break;
        }
        size_t maskOffset = header;
        if (masked) {
            header += 4;
        }
        if (in.size() - offset < header + length) {
            break;
        }
        const uint8_t *body = data + offset + header;
        std::vector<uint8_t> unmasked;
        if (masked) {
            unmasked.assign(body, body + length);
            const uint8_t *mask = data + offset + maskOffset;
            for (size_t a = 0; a < unmasked.size(); a++) {
                unmasked[a] ^= mask[a & 3];
            }
            body = unmasked.data();
        }
        offset += header + (size_t) length;

        if (opcode == kOpBinary || opcode == kOpContinuation) {
            payload.insert(payload.end(), body, body + length);
        } else if (opcode == kOpPing) {
            queueFrame(kOpPong, body, (size_t) length);
        } else if (opcode == kOpPong) {
        } else if (opcode == kOpClose) {
            uint16_t code = length >= 2 ? (uint16_t) ((body[0] << 8) | body[1]) : kCloseNoStatus;
            std::string reason = length > 2 ? std::string((const char *) body + 2, (size_t) length - 2) : "";
            if (LOGS_ENABLED) DEBUG_D("gateway: closed with %d (%s)", code, reason.c_str());
            queueClose(code == kCloseNoStatus ? kCloseNormal : code);
            state = GatewayStateClosed;
            if (code == kCloseNormal || code == kCloseGoingAway || code == kCloseNoStatus) {
                result = StepClosedNormally;
            } else {
                result = fail(code, reason);
            }
            break;
        } else if (opcode == kOpText) {
            queueClose(kCloseUnsupportedData);
            result = fail(GatewayErrorProtocol, "text frame");
            break;
        } else {
            queueClose(kCloseProtocolError);
            result = fail(GatewayErrorProtocol, "bad opcode");
            break;
        }
    }
    in.erase(in.begin(), in.begin() + std::min(offset, in.size()));
    return result;
}

void GatewaySession::queueBinary(const uint8_t *data, uint32_t size) {
    queueFrame(kOpBinary, data, size);
}

void GatewaySession::queueClose(uint16_t code) {
    uint8_t payload[2] = { (uint8_t) (code >> 8), (uint8_t) (code & 0xff) };
    queueFrame(kOpClose, payload, sizeof(payload));
}

void GatewaySession::queueFrame(uint8_t opcode, const uint8_t *data, size_t size) {
    uint8_t header[14];
    size_t headerSize = 2;
    header[0] = (uint8_t) (0x80 | opcode);
    if (size < 126) {
        header[1] = (uint8_t) (0x80 | size);
    } else if (size < 65536) {
        header[1] = 0x80 | 126;
        header[2] = (uint8_t) (size >> 8);
        header[3] = (uint8_t) (size & 0xff);
        headerSize = 4;
    } else {
        header[1] = 0x80 | 127;
        for (int a = 0; a < 8; a++) {
            header[2 + a] = (uint8_t) (((uint64_t) size >> (8 * (7 - a))) & 0xff);
        }
        headerSize = 10;
    }
    uint8_t *mask = header + headerSize;
    RAND_bytes(mask, 4);
    headerSize += 4;

    if (outOffset > 0 && outOffset == out.size()) {
        out.clear();
        outOffset = 0;
    }
    size_t start = out.size();
    out.resize(start + headerSize + size);
    memcpy(out.data() + start, header, headerSize);
    uint8_t *body = out.data() + start + headerSize;
    for (size_t a = 0; a < size; a++) {
        body[a] = data[a] ^ mask[a & 3];
    }
}

bool GatewaySession::hasPendingWrite() const {
    return outOffset < out.size();
}

GatewaySession::StepResult GatewaySession::flush() {
    if (ssl == nullptr || state < GatewayStateUpgrading) {
        return StepOk;
    }
    wantWrite = false;
    while (outOffset < out.size()) {
        size_t chunk = std::min(out.size() - outOffset, (size_t) 64 * 1024);
        int written = SSL_write(ssl, out.data() + outOffset, (int) chunk);
        if (written > 0) {
            Gateway::bytesSent += written;
            outOffset += (size_t) written;
            continue;
        }
        int error = SSL_get_error(ssl, written);
        if (error == SSL_ERROR_WANT_WRITE) {
            wantWrite = true;
            return StepOk;
        } else if (error == SSL_ERROR_WANT_READ) {
            return StepOk;
        }
        ERR_clear_error();
        if (LOGS_ENABLED) DEBUG_E("gateway: TLS write failed, ssl error %d", error);
        return StepError;
    }
    out.clear();
    outOffset = 0;
    return StepOk;
}

GatewaySession::StepResult GatewaySession::fail(int32_t code, const std::string &reason) {
    errorCode = code;
    errorReason = reason;
    return StepError;
}
