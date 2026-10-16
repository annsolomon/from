package in.dexter.testkit.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A loopback TCP proxy with a kill switch, for outage tests (Redis down, broker unreachable) without
 * touching the shared container. {@link #cut()} closes every open connection and refuses new ones (accept,
 * then close); {@link #restore()} forwards again. Clients see exactly what a network partition or a crashed
 * server looks like: reset connections and failed reconnects.
 */
public final class TcpProxy implements AutoCloseable {
  private final ServerSocket server;
  private final String targetHost;
  private final int targetPort;
  private final Set<Socket> open = ConcurrentHashMap.newKeySet();
  private volatile boolean cut;
  private volatile boolean closed;

  private TcpProxy(String targetHost, int targetPort) throws IOException {
    this.targetHost = targetHost;
    this.targetPort = targetPort;
    this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread t = new Thread(this::acceptLoop, "tcp-proxy-" + server.getLocalPort());
    t.setDaemon(true);
    t.start();
  }

  public static TcpProxy to(String host, int port) {
    try {
      return new TcpProxy(host, port);
    } catch (IOException e) {
      throw new IllegalStateException("cannot start proxy", e);
    }
  }

  public String host() {
    return server.getInetAddress().getHostAddress();
  }

  public int port() {
    return server.getLocalPort();
  }

  public boolean isCut() {
    return cut;
  }

  /** Drops every connection now and refuses new ones until {@link #restore()}. */
  public void cut() {
    cut = true;
    for (Socket s : open) {
      closeQuietly(s);
    }
    open.clear();
  }

  public void restore() {
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
    Thread.ofVirtual().name("tcp-proxy-pump").start(() -> {
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
