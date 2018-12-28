package com.qiniu.service.media;

import com.qiniu.persistence.FileMap;
import com.qiniu.common.QiniuException;
import com.qiniu.sdk.OperationManager;
import com.qiniu.service.interfaces.ILineProcess;
import com.qiniu.storage.Configuration;
import com.qiniu.util.Auth;
import com.qiniu.util.HttpResponseUtils;
import com.qiniu.util.StringMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QiniuPfop implements ILineProcess<Map<String, String>>, Cloneable {

    private String processName;
    private Auth auth;
    private Configuration configuration;
    private OperationManager operationManager;
    private String bucket;
    private String fopsIndex;
    private StringMap pfopParams;
    public int retryCount;
    protected String resultPath;
    private int resultIndex;
    public FileMap fileMap;

    public QiniuPfop(Auth auth, Configuration configuration, String bucket, String pipeline, String fopsIndex,
                     String resultPath, int resultIndex) throws IOException {
        this.processName = "pfop";
        this.auth = auth;
        this.configuration = configuration;
        this.operationManager = new OperationManager(auth, configuration);
        this.bucket = bucket;
        if (fopsIndex == null || "".equals(fopsIndex)) throw new IOException("please set the fopsIndex.");
        else this.fopsIndex = fopsIndex;
        this.pfopParams = new StringMap().putNotEmpty("pipeline", pipeline);
        this.resultPath = resultPath;
        this.resultIndex = resultIndex;
        this.fileMap = new FileMap();
        this.fileMap.initWriter(resultPath, processName, resultIndex);
    }

    public QiniuPfop(Auth auth, Configuration configuration, String bucket, String pipeline, String fopsIndex,
                     String resultPath) throws IOException {
        this(auth, configuration, bucket, pipeline, fopsIndex, resultPath, 0);
    }

    public QiniuPfop clone() throws CloneNotSupportedException {
        QiniuPfop qiniuPfop = (QiniuPfop)super.clone();
        qiniuPfop.operationManager = new OperationManager(auth, configuration);
        qiniuPfop.fileMap = new FileMap();
        try {
            qiniuPfop.fileMap.initWriter(resultPath, processName, resultIndex++);
        } catch (IOException e) {
            throw new CloneNotSupportedException("init writer failed.");
        }
        return qiniuPfop;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getProcessName() {
        return this.processName;
    }

    public String singleWithRetry(String key, String fops, int retryCount) throws QiniuException {

        String persistentId = null;
        try {
            persistentId = operationManager.pfop(bucket, key, fops, pfopParams);
        } catch (QiniuException e1) {
            HttpResponseUtils.checkRetryCount(e1, retryCount);
            while (retryCount > 0) {
                try {
                    persistentId = operationManager.pfop(bucket, key, fops, pfopParams);
                    retryCount = 0;
                } catch (QiniuException e2) {
                    retryCount = HttpResponseUtils.getNextRetryCount(e2, retryCount);
                }
            }
        }

        return persistentId;
    }

    public void processLine(List<Map<String, String>> lineList) throws IOException {

        List<String> resultList = new ArrayList<>();
        for (Map<String, String> line : lineList) {
            try {
                String result = singleWithRetry(line.get("key"), line.get(fopsIndex), retryCount);
                if (result != null && !"".equals(result)) resultList.add(line.get("key") + "\t" + result);
                else fileMap.writeError( String.valueOf(line) + "\tempty pfop persistent id");
            } catch (QiniuException e) {
                HttpResponseUtils.processException(e, fileMap, String.valueOf(line));
            }
        }
        if (resultList.size() > 0) fileMap.writeSuccess(String.join("\n", resultList));
    }

    public void closeResource() {
        fileMap.closeWriter();
    }
}