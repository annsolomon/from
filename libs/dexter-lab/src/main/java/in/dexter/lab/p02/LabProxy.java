package in.dexter.lab.p02;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback TCP proxy between the lab's Redis client and Redis, so the trail can simulate a Redis outage
 * ({@link #cut()}: drop connections, refuse new ones) without touching the shared container. Same behaviour as
 * dexter-test's {@code TcpProxy} (the lab cannot depend on the test kit).
 */
final class LabProxy implements AutoCloseable {
  private final ServerSocket server;
  private final String targetHost;
  private final int targetPort;
  private final Set<Socket> open = ConcurrentHashMap.newKeySet();
  private volatile boolean cut;
  private volatile boolean closed;

  private LabProxy(String targetHost, int targetPort) throws IOException {
    this.targetHost = targetHost;
    this.targetPort = targetPort;
    this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread t = new Thread(this::acceptLoop, "p02-lab-redis-proxy");
    t.setDaemon(true);
    t.start();
  }

  static LabProxy to(String host, int port) {
    try {
      return new LabProxy(host, port);
    } catch (IOException e) {
      throw new IllegalStateException("cannot start the lab Redis proxy", e);
    }
  }

  String host() {
    return server.getInetAddress().getHostAddress();
  }

  int port() {
    return server.getLocalPort();
  }

  boolean isCut() {
    return cut;
  }

  void cut() {
    cut = true;
    for (Socket s : open) {
      closeQuietly(s);
    }
    open.clear();
  }

  void restore() {
    cut = false;
  }

  @Override
  public void close() {
    closed = true;
    cut();
    closeQuietly(server);
  }

  private void acceptLoop() {
    while (!closed) {
      Socket client;
      try {
        client = server.accept();
      } catch (IOException e) {
        if (closed) {
          return;
        }
        continue;
      }
      if (cut) {
        closeQuietly(client);
        continue;
      }
      Socket upstream;
      try {
        upstream = new Socket(targetHost, targetPort);
      } catch (IOException e) {
        closeQuietly(client);
        continue;
      }
      open.add(client);
      open.add(upstream);
      pump(client, upstream);
      pump(upstream, client);
    }
  }

  private void pump(Socket from, Socket to) {
    Thread.ofVirtual().name("p02-lab-proxy-pump").start(() -> {
      byte[] buf = new byte[16 * 1024];
      try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
        int n;
        while ((n = in.read(buf)) >= 0) {
          out.write(buf, 0, n);
          out.flush();
        }
      } catch (IOException ignored) {
        // either side closed
      } finally {
        closeQuietly(from);
        closeQuietly(to);
        open.remove(from);
        open.remove(to);
      }
    });
  }

  private static void closeQuietly(AutoCloseable c) {
    try {
      c.close();
    } catch (Exception ignored) {
      // closing anyway
    }
  }
}
