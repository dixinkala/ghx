package com.ghx.accel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.ghx.accel.core.DnsRouter;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 只接管 DNS 的本地 VPN：github 相关域名回优选 IP，其余查询转发上游。
 * 解析与封包逻辑在 DnsRouter（纯 Java，可单测）。
 */
public class DnsVpn extends VpnService implements Runnable {

    public static final String ACTION_START = "com.ghx.accel.START";
    public static final String ACTION_STOP = "com.ghx.accel.STOP";
    private static final String DNS_IP = "10.66.66.1";
    private static final String VPN_IP = "10.66.66.2";
    private static final String UPSTREAM = "223.5.5.5";
    private static final int TTL = 300;

    private static volatile boolean running = false;
    /** 启动失败的原因，供界面提示。 */
    public static volatile String LAST_ERROR = null;

    private ParcelFileDescriptor tun;
    private Thread worker;
    private FileOutputStream out;
    private DnsRouter router;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile long lastNotification = 0;

    public static boolean isRunning() {
        return running;
    }

    private final DnsRouter.Sink sink = new DnsRouter.Sink() {
        public void send(byte[] packet) {
            write(packet);
        }
    };

    private final DnsRouter.Upstream upstream = new DnsRouter.Upstream() {
        public byte[] resolve(byte[] query) throws Exception {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s);
                s.setSoTimeout(4000);
                InetAddress up = InetAddress.getByName(UPSTREAM);
                s.send(new DatagramPacket(query, query.length, up, 53));
                byte[] buf = new byte[4096];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                s.receive(resp);
                return Arrays.copyOf(buf, resp.getLength());
            }
        }
    };

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            return START_NOT_STICKY;
        }
        if (!running) startTunnel();
        return START_STICKY;
    }

    private void startTunnel() {
        LAST_ERROR = null;
        try {
            DnsConfig.load(this);
            Builder b = new Builder();
            b.setSession("ghx 加速");
            b.addAddress(VPN_IP, 32);
            b.addDnsServer(DNS_IP);
            b.addRoute(DNS_IP, 32);
            b.setMtu(1500);
            b.setBlocking(true);
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
            tun = b.establish();
            if (tun == null) {
                LAST_ERROR = "系统拒绝建立 VPN";
                running = false;
                stopSelf();
                return;
            }
            out = new FileOutputStream(tun.getFileDescriptor());
            router = new DnsRouter(new DnsRouter.Mapper() {
                public String ipFor(String host) {
                    return DnsConfig.get(host);
                }
            }, DNS_IP, TTL);
            running = true;
            notify("DNS 加速已开启");
            worker = new Thread(this);
            worker.setName("ghx-dns");
            worker.start();
        } catch (Exception e) {
            LAST_ERROR = e.getClass().getSimpleName() + ": " + e.getMessage();
            running = false;
            stopTunnel();
        }
    }

    private void stopTunnel() {
        running = false;
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) { }
        tun = null;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        stopSelf();
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) { }
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void run() {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            while (running) {
                int n = in.read(buf);
                if (n <= 0) continue;
                final byte[] pkt = Arrays.copyOf(buf, n);
                pool.submit(new Runnable() {
                    public void run() {
                        try {
                            router.handle(pkt, sink, upstream);
                        } catch (Exception ignored) {
                            // 单个畸形包不影响整体
                        }
                    }
                });
            }
        } catch (Exception ignored) {
            // 关闭 tunnel 时读取失败，正常退出
        }
    }

    private synchronized void write(byte[] packet) {
        try {
            if (out != null) out.write(packet);
        } catch (Exception ignored) { }
    }

    private void notify(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            String ch = "ghx_vpn";
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(ch, "加速状态",
                        NotificationManager.IMPORTANCE_LOW));
            }
            long now = System.currentTimeMillis();
            if (now - lastNotification < 1500) return;
            lastNotification = now;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, ch) : new Notification.Builder(this);
            b.setContentTitle("GitHub 加速器").setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(true);
            nm.notify(1, b.build());
        } catch (Exception ignored) {
            // 没有通知权限也不影响加速
        }
    }
}
