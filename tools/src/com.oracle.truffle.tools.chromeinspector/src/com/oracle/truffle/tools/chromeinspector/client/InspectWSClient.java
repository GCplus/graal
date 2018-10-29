/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.tools.chromeinspector.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.graalvm.polyglot.io.MessageEndpoint;
import com.oracle.truffle.api.TruffleOptions;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.oracle.truffle.tools.chromeinspector.TruffleExecutionContext;
import com.oracle.truffle.tools.chromeinspector.instrument.InspectorWSConnection;
import com.oracle.truffle.tools.chromeinspector.instrument.KeyStoreOptions;
import com.oracle.truffle.tools.chromeinspector.server.ConnectionWatcher;
import com.oracle.truffle.tools.chromeinspector.server.InspectServerSession;

/**
 * Web socket client that connects to a listening inspector client.
 */
public class InspectWSClient extends WebSocketClient implements InspectorWSConnection {

    private final String host;
    private final int port;
    private final TruffleExecutionContext executionContext;
    private final boolean debugBreak;
    private final ConnectionWatcher connectionWatcher;
    private final PrintStream traceLog;
    private InspectServerSession iss;

    private static URI getURI(InetSocketAddress isa, String wsspath) {
        try {
            return new URI("ws://" + isa.getHostString() + ":" + isa.getPort() + wsspath);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public InspectWSClient(InetSocketAddress isa, String wsspath, TruffleExecutionContext executionContext, boolean debugBreak, boolean secure, KeyStoreOptions keyStoreOptions,
                    ConnectionWatcher connectionWatcher, PrintWriter info) throws IOException {
        super(getURI(isa, wsspath));
        this.host = isa.getHostString();
        this.port = isa.getPort();
        this.executionContext = executionContext;
        this.debugBreak = debugBreak;
        this.connectionWatcher = connectionWatcher;
        String traceLogFile = System.getProperty("chromeinspector.traceMessages");
        if (traceLogFile != null) {
            if (Boolean.parseBoolean(traceLogFile)) {
                traceLog = System.err;
            } else if (!"false".equalsIgnoreCase(traceLogFile)) {
                if ("tmp".equalsIgnoreCase(traceLogFile)) {
                    traceLog = new PrintStream(new FileOutputStream(File.createTempFile("ChromeInspectorProtocol", ".txt")));
                } else {
                    traceLog = new PrintStream(new FileOutputStream(traceLogFile));
                }
            } else {
                traceLog = null;
            }
        } else {
            traceLog = null;
        }
        if (secure) {
            if (TruffleOptions.AOT) {
                throw new IOException("Secure connection is not available in the native-image yet.");
            } else {
                setSocket(createSecureSocket(keyStoreOptions));
            }
        }
        try {
            boolean success = connectBlocking();
            if (!success) {
                info.println("Could not attach to " + host + ":" + port);
                info.flush();
            }
        } catch (InterruptedException ex) {
            throw new IOException("Interrupted " + ex.getLocalizedMessage());
        }
    }

    private static Socket createSecureSocket(KeyStoreOptions keyStoreOptions) throws IOException {
        String keyStoreFile = keyStoreOptions.getKeyStore();
        if (keyStoreFile != null) {
            try {
                String filePasswordProperty = keyStoreOptions.getKeyStorePassword();
                // obtaining password for unlock keystore
                char[] filePassword = filePasswordProperty == null ? "".toCharArray() : filePasswordProperty.toCharArray();
                String keystoreType = keyStoreOptions.getKeyStoreType();
                if (keystoreType == null) {
                    keystoreType = KeyStore.getDefaultType();
                }
                KeyStore keystore = KeyStore.getInstance(keystoreType);
                File keyFile = new File(keyStoreFile);
                keystore.load(new FileInputStream(keyFile), filePassword);
                String keyRecoverPasswordProperty = keyStoreOptions.getKeyPassword();
                char[] keyRecoverPassword = keyRecoverPasswordProperty == null ? filePassword : keyRecoverPasswordProperty.toCharArray();
                final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keystore, keyRecoverPassword);
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(keystore);

                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
                return sslContext.getSocketFactory().createSocket();
            } catch (KeyStoreException | KeyManagementException | NoSuchAlgorithmException | CertificateException | UnrecoverableKeyException ex) {
                throw new IOException(ex);
            }
        } else {
            throw new IOException("Use options to specify the keystore");
        }
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public void onOpen(ServerHandshake sh) {
        if (traceLog != null) {
            traceLog.println("CLIENT ws connection opened at " + getURI());
            traceLog.flush();
        }
        iss = InspectServerSession.create(executionContext, debugBreak, connectionWatcher);
        connectionWatcher.notifyOpen();
        iss.setMessageListener(new MessageEndpoint() {
            @Override
            public void sendText(String message) {
                if (traceLog != null) {
                    traceLog.println("SERVER: " + message);
                    traceLog.flush();
                }
                send(message);
            }

            @Override
            public void sendBinary(ByteBuffer data) throws IOException {
                throw new UnsupportedOperationException("Binary messages are not supported.");
            }

            @Override
            public void sendPing(ByteBuffer data) throws IOException {
            }

            @Override
            public void sendPong(ByteBuffer data) throws IOException {
            }

            @Override
            public void sendClose() throws IOException {
                close();
            }
        });
    }

    @Override
    public void onMessage(String message) {
        if (traceLog != null) {
            traceLog.println("CLIENT: " + message);
            traceLog.flush();
        }
        iss.sendText(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (traceLog != null) {
            traceLog.println("SERVER closed " + reason);
            traceLog.flush();
        }
        connectionWatcher.notifyClosing();
        if (!executionContext.canRun()) {
            // The connection was not successfull, resume the execution
            executionContext.doRunIfWaitingForDebugger();
        }
    }

    @Override
    public void onError(Exception excptn) {
        if (traceLog != null) {
            traceLog.println("SERVER error " + excptn);
            traceLog.flush();
        }
    }

    @Override
    public void close(String wsspath) {
        close();
    }

}