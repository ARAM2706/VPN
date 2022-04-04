package com.aram.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class VPNService extends VpnService implements Handler.Callback {

    public static final String TAG = VPNService.class.getSimpleName();

    public static final String ACTION_CONNECT = "com.aram.vpn.START";
    public static final String ACTION_DISCONNECT = "com.aram.vpn.STOP";

    private Handler mHandler;

    private static class Connection extends Pair<Thread,ParcelFileDescriptor> {

        /**
         * Constructor for a Pair.
         *
         * @param first  the first object in the Pair
         * @param second the second object in the pair
         */
        public Connection(@Nullable Thread first, @Nullable ParcelFileDescriptor second) {
            super(first, second);
        }
    }

    private final AtomicReference<Thread> mConnectingThread = new AtomicReference<>();
    private final AtomicReference<Connection> mConnection = new AtomicReference<>();

    private AtomicInteger mNextConnectionId = new AtomicInteger(1);

    private PendingIntent mConfigureIntent;

    public VPNService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // El Handler se utiliza solamente para mostrar mensajes
        if(mHandler == null) {
            mHandler = new Handler(this);
        }

        //Crea el intent para configurar la conexion (solamente inicia el cliente VPN)
        mConfigureIntent = PendingIntent.getActivity(this,0,new Intent(this, VPNClient.class),PendingIntent.FLAG_UPDATE_CURRENT);
    }


    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && ACTION_CONNECT.equals(intent.getAction())){
            disconnect();
            return START_NOT_STICKY;
        }else {
            connect();
            return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        Toast.makeText(this,msg.what,Toast.LENGTH_SHORT).show();
        if(msg.what != R.string.disconnected){
            updateForegroundNotification(msg.what);
        }
        return true;
    }

    private void connect(){
        /*
        * Se convierte en un servicio en primer plano. Los servicios en segundo plano pueden ser
        * servicios VPN tambien, pero estos pueden ser eliminados por el verificador en segundo plano
        * antes de tener oportunidad de recivir onRevoke().
        * */
        updateForegroundNotification(R.string.connecting);
        mHandler.sendEmptyMessage(R.string.connecting);
        mHandler.sendEmptyMessage(R.string.connected);
        //Toast.makeText(this, "VPN is connected", Toast.LENGTH_SHORT).show();

        //Extraer la informacion de SharedPreferences
        final SharedPreferences prefs = getSharedPreferences(VPNClient.Prefs.NAME,MODE_PRIVATE);
        final String server = prefs.getString(VPNClient.Prefs.SERVER_ADDRESS,"");
        final byte[] secret = prefs.getString(VPNClient.Prefs.SHARED_SECRET,"").getBytes();
        final boolean allow = prefs.getBoolean(VPNClient.Prefs.ALLOW,true);
        final Set<String> packages = prefs.getStringSet(VPNClient.Prefs.PACKAGES, Collections.emptySet());
        final int port = prefs.getInt(VPNClient.Prefs.SERVER_PORT, 0);
        final String proxyHost = prefs.getString(VPNClient.Prefs.PROXY_HOSTNAME, "");
        final int proxyPort = prefs.getInt(VPNClient.Prefs.PROXY_PORT, 0);
        startConnection(new VpnConnection(this, mNextConnectionId.getAndIncrement(), server, port, secret, proxyHost, proxyPort, allow, packages));
    }

    private void startConnection(final VpnConnection connection){
        // Remplaza cualquier hilo de conexion con este nuevo.
        final Thread thread = new Thread(connection,"VpnThread");
        setConnectingThread(thread);

        // Handler to mark as connected once onEstablish is called.
        connection.setConfigureIntent(mConfigureIntent);
        connection.setOnEstablishListener(tunInterface -> {
            mHandler.sendEmptyMessage(R.string.connected);
            mConnectingThread.compareAndSet(thread, null);
            setConnection(new Connection(thread, tunInterface));
        });
        thread.start();

    }

    private void setConnectingThread(final Thread thread) {
        final Thread oldThread = mConnectingThread.getAndSet(thread);
        if (oldThread != null) {
            oldThread.interrupt();
        }
    }
    private void setConnection(final Connection connection) {
        final Connection oldConnection = mConnection.getAndSet(connection);
        if (oldConnection != null) {
            try {
                oldConnection.first.interrupt();
                oldConnection.second.close();
            } catch (IOException e) {
                Log.e(TAG, "Closing VPN interface", e);
            }
        }
    }
    private void disconnect() {
        mHandler.sendEmptyMessage(R.string.disconnected);
        setConnectingThread(null);
        setConnection(null);
        stopForeground(true);
    }
    private void updateForegroundNotification(final int message) {
        final String NOTIFICATION_CHANNEL_ID = "ToyVpn";
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(
                NOTIFICATION_SERVICE);
        mNotificationManager.createNotificationChannel(new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(1, new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentText(getString(message))
                .setContentIntent(mConfigureIntent)
                .build());
    }
}