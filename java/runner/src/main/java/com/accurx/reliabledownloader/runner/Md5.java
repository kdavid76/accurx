package com.accurx.reliabledownloader.runner;

import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;

public class Md5 {

    public static String contentMd5(File file) throws IOException
    {
        return BaseEncoding.base64().encode(
            Files.asByteSource(file).hash(Hashing.md5()).asBytes()
        );
    }
}
