package com.vaenow.appupdate.android;

import android.AuthenticationOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.widget.ProgressBar;
import com.cabbage.domain.DomainChain;
import com.cabbage.domain.DomainChainService;
import com.cabbage.domain.RequestURLType;
import com.cabbage.httploader.exceptions.BadHostException;
import com.cabbage.httploader.exceptions.HasNotAvailableDomainsException;
import com.cabbage.httploader.exceptions.NoInternetConnectionException;
import com.cabbage.httploader.exceptions.UnsupportedHttpVerbException;
import com.cabbage.net.NetworkState;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.HashMap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 下载文件线程
 */
public class DownloadApkThread implements Runnable {
    private String TAG = "DownloadApkThread";

    /* 保存解析的XML信息 */
    HashMap<String, String> mHashMap;
    /* 下载保存路径 */
    private String mSavePath;
    /* 记录进度条数量 */
    private int progress;
    /* 是否取消更新 */
    private boolean cancelUpdate = false;
    private AlertDialog mDownloadDialog;
    private DownloadHandler downloadHandler;
    private Handler mHandler;
    private AuthenticationOptions authentication;

    OkHttpClient client = new OkHttpClient();
    private DomainChain activeDomain;

    public DownloadApkThread(Context mContext, Handler mHandler, ProgressBar mProgress, AlertDialog mDownloadDialog, HashMap<String, String> mHashMap, JSONObject options) {
        this.mDownloadDialog = mDownloadDialog;
        this.mHashMap = mHashMap;
        this.mHandler = mHandler;
        this.authentication = new AuthenticationOptions(options);

        this.mSavePath = Environment.getExternalStorageDirectory() + "/" + "download"; // SD Path
        this.downloadHandler = new DownloadHandler(mContext, mProgress, mDownloadDialog, this.mSavePath, mHashMap);
    }


    @Override
    public void run() {
        downloadAndInstall();
        // 取消下载对话框显示
        // mDownloadDialog.dismiss();
    }

    public void cancelBuildUpdate() {
        this.cancelUpdate = true;
    }

    private void downloadAndInstall() {
        try {
            // 判断SD卡是否存在，并且是否具有读写权限
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                // 获得存储卡的路径

                Response response;
                try {
                    response = tryLoadResponse(mHashMap.get("url"));
                } catch (Throwable e) {
                    mHandler.sendEmptyMessage(Constants.NETWORK_ERROR);
                    mDownloadDialog.cancel();
                    return;
                }


                InputStream is = response.body().byteStream();
                long length = response.body().contentLength();

                File file = new File(mSavePath);
                // 判断文件目录是否存在
                if (!file.exists()) {
                    file.mkdir();
                }
                File apkFile = new File(mSavePath, mHashMap.get("name")+".apk");
                FileOutputStream fos = new FileOutputStream(apkFile);
                int count = 0;
                // 缓存
                byte buf[] = new byte[1024];

                // 写入到文件中
                do {
                    int numread = is.read(buf);
                    count += numread;

                    progress = (int) (((float) count / length) * 100);
                    downloadHandler.updateProgress(progress);

                    // 更新进度
                    downloadHandler.sendEmptyMessage(Constants.DOWNLOAD);
                    if (numread <= 0) {
                        // 下载完成
                        downloadHandler.sendEmptyMessage(Constants.DOWNLOAD_FINISH);
                        mHandler.sendEmptyMessage(Constants.DOWNLOAD_FINISH);
                        break;
                    }
                    // 写入文件
                    fos.write(buf, 0, numread);
                } while (!cancelUpdate);// 点击取消就停止下载.
                fos.close();
                is.close();
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            mHandler.sendEmptyMessage(Constants.NETWORK_ERROR);
            mDownloadDialog.cancel();
        }

    }

    // ------------------------------------------------------------------------------------------


    private Response tryLoadResponse(String route) throws BadHostException, HasNotAvailableDomainsException, UnsupportedHttpVerbException, NoInternetConnectionException {
        Response response;
        try {
            response = loadContent(route);

            if (!response.isSuccessful())
                if (!ping(activeDomain.getSni(), "https://" + activeDomain.getCdn()))
                    throw new BadHostException();

            return response;

        } catch (BadHostException e) {
            // if (!NetworkState.isAccessToNetwork())
            //     throw new NoInternetConnectionException();

            if (!activeDomain.isLockable())
                throw e;

            activeDomain.fail();
            return tryLoadResponse(route);
        }
    }

    private Response loadContent(String route) throws BadHostException, HasNotAvailableDomainsException, UnsupportedHttpVerbException {
        // Loads a CDN provider and sets it  to member variable
        activeDomain = DomainChainService.getInstance().getDomainChain(RequestURLType.APPUPDATE);
        String sni = activeDomain.getSni();
        String fullUrl = "https://" + activeDomain.getCdn() + "/" + route;

        //Perform the proper http verb
        try {
            return performHttpGet(sni, fullUrl, activeDomain.getHost());
        } catch (IOException e) {
            if (activeDomain.isRepeatable())
                try {
                    return performHttpGet(sni, fullUrl, activeDomain.getHost());
                } catch (IOException e1) {}
            throw new BadHostException();
        }
    }


    private Response performHttpGet(String sni, String fullUrl, String host) throws BadHostException, IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(fullUrl)
                .sni(sni)
                .addHeader("Host", host)
                .get();

        try {
            return client.newCall(requestBuilder.build()).execute();
        } catch (UnknownHostException e) {
            throw new BadHostException();
        } catch (IOException e) {
            throw e;
        }
    }

    private boolean ping(String sni, String url) {
        try {
            Response pingResult;
            Request.Builder requestBuilder = new Request.Builder().url(url).sni(sni).head();
            requestBuilder.addHeader("Host", activeDomain.getHost());

            try {
                pingResult = client.newCall(requestBuilder.build()).execute();
            } catch (IOException e) {
                throw new BadHostException();
            }

            if (pingResult.isSuccessful())
                return true;
            else
                return false;
        } catch (BadHostException e) {
            return false;
        }
    }
}
