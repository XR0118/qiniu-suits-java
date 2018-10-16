package com.qiniu.service.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.qiniu.common.FileReaderAndWriterMap;
import com.qiniu.common.QiniuAuth;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.interfaces.IBucketProcess;
import com.qiniu.interfaces.IOssFileProcess;
import com.qiniu.service.oss.ListBucket;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.util.JsonConvertUtils;
import com.qiniu.util.StringUtils;
import com.qiniu.util.UrlSafeBase64;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ListBucketProcess implements IBucketProcess {

    private QiniuAuth auth;
    private Configuration configuration;
    private String bucket;
    private String resultFileDir;
    private FileReaderAndWriterMap fileReaderAndWriterMap = new FileReaderAndWriterMap();

    public ListBucketProcess(QiniuAuth auth, Configuration configuration, String bucket, String resultFileDir)
            throws IOException {

        this.auth = auth;
        this.configuration = configuration;
        this.bucket = bucket;
        this.resultFileDir = resultFileDir;
        fileReaderAndWriterMap.initWriter(resultFileDir, "list");
    }

    private void writeAndProcess(List<FileInfo> fileInfoList, FileReaderAndWriterMap fileMap, int retryCount, boolean processBatch,
                                 IOssFileProcess iOssFileProcessor, Queue<QiniuException> exceptionQueue)
            throws QiniuException {

        if (fileMap != null) fileMap.writeSuccess(
                String.join("\n", fileInfoList.parallelStream()
                        .map(JsonConvertUtils::toJson)
                        .collect(Collectors.toList()))
        );

        if (iOssFileProcessor != null) {
            fileInfoList.parallelStream().forEach(fileInfo -> {
                iOssFileProcessor.processFile(fileInfo, retryCount, processBatch);
                if (iOssFileProcessor.qiniuException() != null && iOssFileProcessor.qiniuException().code() > 400)
                    exceptionQueue.add(iOssFileProcessor.qiniuException());
            });
        }

        if (exceptionQueue != null) {
            QiniuException qiniuException = exceptionQueue.poll();
            if (qiniuException != null) throw qiniuException;
        }
    }

    public FileInfo getFirstFileInfo(Response response, String line, int version) {

        FileInfo fileInfo = new FileInfo();
        try {
            if (version == 1) {
                if (response != null) {
                    FileListing fileListing = response.jsonToObject(FileListing.class);
                    fileInfo = fileListing.items != null && fileListing.items.length > 0 ? fileListing.items[0] : null;
                }
            } else if (version == 2) {
                if (response != null || !StringUtils.isNullOrEmpty(line)) {
                    if (response != null) {
                        List<String> lineList = Arrays.asList(response.bodyString().split("\n"));
                        line = lineList.size() > 0 ? lineList.get(0) : null;
                    }
                    if (!StringUtils.isNullOrEmpty(line)) {
                        JsonObject json = JsonConvertUtils.toJsonObject(line);
                        JsonElement item = json.get("item");
                        if (item != null && !(item instanceof JsonNull)) {
                            fileInfo = JsonConvertUtils.fromJson(item, FileInfo.class);
                        }
                    }
                }
            }
        } catch (QiniuException e) {}

        return fileInfo;
    }

    private List<FileInfo> listByPrefix(ListBucket listBucket, List<String> prefixList, int version, IOssFileProcess processor,
                                        int retryCount) throws QiniuException {

        Queue<QiniuException> exceptionQueue = new ConcurrentLinkedQueue<>();
        List<FileInfo> fileInfoList = prefixList.parallelStream()
//                .filter(prefix -> !prefix.contains("|"))
                .map(prefix -> {
                    Response response = null;
                    FileInfo firstFileInfo = null;
                    try {
                        response = listBucket.run(bucket, prefix, null, null, 1, 3, version);
                        firstFileInfo = getFirstFileInfo(response, null, version);
                    } catch (QiniuException e) {
                        fileReaderAndWriterMap.writeErrorOrNull(bucket + "\t" + prefix + "\t" + e.error());
                        if (e.code() > 400) exceptionQueue.add(e);
                    } finally { if (response != null) response.close(); }
                    return firstFileInfo;
                })
                .filter(fileInfo -> !(fileInfo == null || StringUtils.isNullOrEmpty(fileInfo.key)))
                .collect(Collectors.toList());

        writeAndProcess(fileInfoList, fileReaderAndWriterMap, retryCount, false, processor, exceptionQueue);
        return fileInfoList;
    }

    private List<String> getSecondFilePrefix(List<String> prefixList, List<FileInfo> delimitedFileInfo) {
        List<String> firstKeyList = delimitedFileInfo.parallelStream()
                .map(fileInfo -> fileInfo.key)
                .collect(Collectors.toList());
        List<String> secondPrefixList = new ArrayList<>();
        for (String firstKey : firstKeyList) {
            String firstPrefix = firstKey.substring(0, 1);
            for (String secondPrefix : prefixList) {
                secondPrefixList.add(firstPrefix + secondPrefix);
            }
        }

        return secondPrefixList;
    }

    public Map<String, String> getDelimitedFileMap(int version, int level, String customPrefix, IOssFileProcess iOssFileProcessor,
                                                   int retryCount) throws QiniuException {

        ListBucket listBucket = new ListBucket(auth, configuration);
        List<FileInfo> fileInfoList;
        List<String> prefixList = Arrays.asList(" !\"#$%&'()*+,-./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~".split(""));
        if (!StringUtils.isNullOrEmpty(customPrefix))
            prefixList = prefixList
                    .parallelStream()
                    .map(prefix -> customPrefix + prefix)
                    .collect(Collectors.toList());
        if (level == 2) {
            fileInfoList = listByPrefix(listBucket, prefixList, version, null, retryCount);
            prefixList = getSecondFilePrefix(prefixList, fileInfoList);
            fileInfoList.addAll(listByPrefix(listBucket, prefixList, version, iOssFileProcessor, retryCount));
        } else {
            fileInfoList = listByPrefix(listBucket, prefixList, version, iOssFileProcessor, retryCount);
        }
        listBucket.closeBucketManager();
        fileReaderAndWriterMap.closeWriter();

        return fileInfoList.parallelStream().collect(Collectors.toMap(
                fileInfo -> fileInfo.key,
                fileInfo -> UrlSafeBase64.encodeToString("{\"c\":" + fileInfo.type + ",\"k\":\"" + fileInfo.key + "\"}")
        ));
    }

    /*
        单次列举请求，可以传递 marker 和 limit 参数，可采用此方法进行并发处理。v1 list 接口直接返回一个全部 limit（上限 1000）范围内的数据，
        v2 的 list 接口返回的是文本的数据流，可通过 java8 的流来处理。
     */
    public List<FileInfo> list(ListBucket listBucket, String bucket, String prefix, String delimiter, String marker,
                         int limit, int version, int retryCount) throws IOException {

        List<FileInfo> fileInfoList = new ArrayList<>();
        Response response = listBucket.run(bucket, prefix, delimiter, marker, limit, retryCount, version);
        if (version == 1) {
            FileListing fileListing = response.jsonToObject(FileListing.class);
            fileInfoList = Arrays.asList(fileListing.items);
        } else if (version == 2) {
            InputStream inputStream = new BufferedInputStream(response.bodyStream());
            Reader reader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(reader);
            Stream<String> lineStream = bufferedReader.lines().parallel();
            fileInfoList = lineStream
                    .map(line -> getFirstFileInfo(null, line, 2))
                    .collect(Collectors.toList());
            bufferedReader.close();
            reader.close();
            inputStream.close();
        }

        response.close();
        return fileInfoList;
    }

    public String getNextMarker(List<FileInfo> fileInfoList, String fileFlag, int unitLen, int version) {

        if (fileInfoList == null || fileInfoList.size() < unitLen) {
            return null;
        } else if (!StringUtils.isNullOrEmpty(fileFlag) && fileInfoList.parallelStream()
                .anyMatch(fileInfo -> fileInfo.key.equals(fileFlag))) {
            return null;
        } else {
            Optional<FileInfo> lastFileInfo = fileInfoList.parallelStream().max(Comparator.comparing(fileInfo -> fileInfo.key));
            return lastFileInfo.isPresent() ? UrlSafeBase64.encodeToString("{\"c\":" + lastFileInfo.get().type +
                    ",\"k\":\"" + lastFileInfo.get().key + "\"}") : null;
        }
    }

    public String listAndProcess(ListBucket listBucket, int unitLen, String prefix, String endFileKey, String marker, int version,
                                  FileReaderAndWriterMap fileMap, IOssFileProcess processor, boolean processBatch) throws IOException {

        List<FileInfo> fileInfoList = list(listBucket, bucket, prefix, "", marker, unitLen, version, 3);
        marker = getNextMarker(fileInfoList, endFileKey, unitLen, version);
        writeAndProcess(fileInfoList, fileMap, 3, processBatch, processor, null);
        return marker;
    }

    public void processBucket(int version, int maxThreads, int level, int unitLen, boolean endFile, String customPrefix,
                              IOssFileProcess iOssFileProcessor, boolean processBatch) throws IOException, CloneNotSupportedException {

        Map<String, String> delimitedFileMap = getDelimitedFileMap(version, level, customPrefix, iOssFileProcessor, 3);
        List<String> keyList = new ArrayList<>(delimitedFileMap.keySet());
        Collections.sort(keyList);
        boolean strictPrefix = !StringUtils.isNullOrEmpty(customPrefix);
        int runningThreads = strictPrefix ? delimitedFileMap.size() : delimitedFileMap.size() + 1;
        runningThreads = runningThreads < maxThreads ? runningThreads : maxThreads;
        System.out.println("there are " + runningThreads + " threads running...");

        ExecutorService executorPool = Executors.newFixedThreadPool(runningThreads);
        for (int i = strictPrefix ? 0 : -1; i < keyList.size(); i++) {
            int finalI = i;
            FileReaderAndWriterMap fileMap = new FileReaderAndWriterMap();
            fileMap.initWriter(resultFileDir, "list");
            IOssFileProcess processor = iOssFileProcessor != null ? iOssFileProcessor.clone() : null;
            executorPool.execute(() -> {
                String endFileKey = "";
                String prefix = "";
                if (endFile && finalI < keyList.size() - 1) {
                    endFileKey = keyList.get(finalI + 1);
                } else if (!endFile && finalI < keyList.size() -1 && finalI > -1) {
                    if (keyList.get(finalI).length() < customPrefix.length() + 2) prefix = keyList.get(finalI);
                    else prefix = keyList.get(finalI).substring(0, customPrefix.length() + level == 2 ? 2 : 1);
                } else {
                    if (finalI == -1) endFileKey = keyList.get(0);
                    if (strictPrefix) prefix = customPrefix;
                }
                String marker = finalI == -1 ? "null" : delimitedFileMap.get(keyList.get(finalI));
                ListBucket listBucket = new ListBucket(auth, configuration);
                while (!StringUtils.isNullOrEmpty(marker)) {
                    try {
                        marker = listAndProcess(listBucket, unitLen, prefix, endFileKey,
                                marker.equals("null") ? "" : marker, version, fileMap, processor, processBatch);
                    } catch (IOException e) {
                        fileMap.writeErrorOrNull(bucket + "\t" + prefix + endFileKey + "\t" + marker + "\t" + unitLen
                                + "\t" + e.getMessage());
                    }
                }
                listBucket.closeBucketManager();
                if (processor != null) {
                    processor.checkBatchProcess(3);
                    processor.closeResource();
                }
                fileMap.closeWriter();
            });
        }

        executorPool.shutdown();
        try {
            while (!executorPool.isTerminated())
                Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}