package com.qiniu.process.qoss;

import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.process.Base;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.BucketManager.*;
import com.qiniu.storage.Configuration;
import com.qiniu.util.Auth;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ChgStatus extends Base {

    final private int status;
    private BucketManager bucketManager;

    public ChgStatus(String accessKey, String secretKey, Configuration configuration, String bucket, int status,
                     String rmPrefix, String savePath, int saveIndex) throws IOException {
        super("status", accessKey, secretKey, configuration, bucket, rmPrefix, savePath, saveIndex);
        this.status = status;
        this.bucketManager = new BucketManager(Auth.create(accessKey, secretKey), configuration);
    }

    public ChgStatus(String accessKey, String secretKey, Configuration configuration, String bucket, int status,
                     String rmPrefix, String savePath) throws IOException {
        this(accessKey, secretKey, configuration, bucket, status, rmPrefix, savePath, 0);
    }

    public ChgStatus clone() throws CloneNotSupportedException {
        ChgStatus changeStatus = (ChgStatus)super.clone();
        changeStatus.bucketManager = new BucketManager(Auth.create(accessKey, secretKey), configuration.clone());
        return changeStatus;
    }

    protected Response batchResult(List<Map<String, String>> lineList) throws QiniuException {
        BatchOperations batchOperations = new BatchOperations();
        lineList.forEach(line -> batchOperations.addChangeStatusOps(bucket, status, line.get("key")));
        return bucketManager.batch(batchOperations);
    }

    protected String singleResult(Map<String, String> line) throws QiniuException {
        return null;
    }
}
