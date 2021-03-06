package com.qiniu.process.qoss;

import com.qiniu.common.QiniuException;
import com.qiniu.process.Base;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.BucketManager.*;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.model.StorageType;
import com.qiniu.util.Auth;
import com.qiniu.util.HttpRespUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ChangeType extends Base<Map<String, String>> {

    private int type;
    private BatchOperations batchOperations;
    private BucketManager bucketManager;

    public ChangeType(String accessKey, String secretKey, Configuration configuration, String bucket, int type) throws IOException {
        super("type", accessKey, secretKey, configuration, bucket);
        this.type = type;
        this.bucketManager = new BucketManager(Auth.create(accessKey, secretKey), configuration.clone());
    }

    public ChangeType(String accessKey, String secretKey, Configuration configuration, String bucket, int type,
                      String savePath, int saveIndex) throws IOException {
        super("type", accessKey, secretKey, configuration, bucket, savePath, saveIndex);
        this.type = type;
        this.batchSize = 1000;
        this.batchOperations = new BatchOperations();
        this.bucketManager = new BucketManager(Auth.create(accessKey, secretKey), configuration.clone());
    }

    public ChangeType(String accessKey, String secretKey, Configuration configuration, String bucket, int type,
                      String savePath) throws IOException {
        this(accessKey, secretKey, configuration, bucket, type, savePath, 0);
    }

    public void updateType(String bucket, int type) {
        this.bucket = bucket;
        this.type = type;
    }

    public ChangeType clone() throws CloneNotSupportedException {
        ChangeType changeType = (ChangeType)super.clone();
        changeType.bucketManager = new BucketManager(Auth.create(accessKey, secretKey), configuration.clone());
        if (batchSize > 1) changeType.batchOperations = new BatchOperations();
        return changeType;
    }

    @Override
    public String resultInfo(Map<String, String> line) {
        return line.get("key");
    }

    @Override
    public boolean validCheck(Map<String, String> line) {
        return line.get("key") != null;
    }

    @Override
    synchronized public String batchResult(List<Map<String, String>> lineList) throws QiniuException {
        batchOperations.clearOps();
        lineList.forEach(line -> batchOperations.addChangeTypeOps(bucket, type == 0 ? StorageType.COMMON :
                StorageType.INFREQUENCY, line.get("key")));
        return HttpRespUtils.getResult(bucketManager.batch(batchOperations));
    }

    @Override
    public String singleResult(Map<String, String> line) throws QiniuException {
        return HttpRespUtils.getResult(bucketManager.changeType(bucket, line.get("key"), type == 0 ?
                StorageType.COMMON : StorageType.INFREQUENCY));
    }
}
