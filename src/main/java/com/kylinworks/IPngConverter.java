package com.kylinworks;

import com.jcraft.jzlib.*;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import java.util.zip.CRC32;

/**
 * @author Rex
 */
public class IPngConverter {

  private static final Logger log = Logger.getLogger(IPngConverter.class.getName());

  private final File source;
  private final File target;
  private ArrayList<PNGTrunk> trunks = null;


  public IPngConverter(File source, File target) {
    if (source == null) throw new NullPointerException("'source' cannot be null");
    if (target == null) throw new NullPointerException("'target' cannot be null");
    this.source = source;
    this.target = target;
  }

  private File getTargetFile(File convertedFile) throws IOException {
    if (source.isFile()) {
      if (target.isDirectory()) {
        return new File(target, source.getName());
      } else {
        return target;
      }

    } else { // source is a directory
      if (target.isFile()) { // single existing target
        return target;

      } else { // otherwise reconstruct a similar directory structure
        if (!target.isDirectory() && !target.mkdirs()) {
          throw new IOException("failed to create folder " + target.getAbsolutePath());
        }

        Path relativeConvertedPath = source.toPath().relativize(convertedFile.toPath());
        File targetFile = new File(target, relativeConvertedPath.toString());
        File targetFileDir = targetFile.getParentFile();
        if (targetFileDir != null && !targetFileDir.exists() && !targetFileDir.mkdirs()) {
          throw new IOException("unable to create folder " + targetFileDir.getAbsolutePath());
        }

       return targetFile;
      }
    }
  }

  public void convert() throws IOException {
    convert(source);
  }

  private boolean isPngFileName(File file) {
    return file.getName().toLowerCase().endsWith(".png");
  }

  private PNGTrunk getTrunk(String szName) {
    if (trunks == null) {
      return null;
    }
    PNGTrunk trunk;
    for (int n = 0; n < trunks.size(); n++) {
      trunk = trunks.get(n);
      if (trunk.getName().equalsIgnoreCase(szName)) {
        return trunk;
      }
    }
    return null;
  }

  private void convertPngFile(File pngFile, File targetFile) throws IOException {
    readTrunks(pngFile);

    if (getTrunk("CgBI") != null) {
      // Convert data

      PNGIHDRTrunk ihdrTrunk = (PNGIHDRTrunk) getTrunk("IHDR");
      log.fine("Width:" + ihdrTrunk.m_nWidth + "  Height:" + ihdrTrunk.m_nHeight);

      int nMaxInflateBuffer = 4 * (ihdrTrunk.m_nWidth + 1) * ihdrTrunk.m_nHeight;
      byte[] outputBuffer = new byte[nMaxInflateBuffer];

      convertDataTrunk(ihdrTrunk, outputBuffer, nMaxInflateBuffer);

      writePng(targetFile);
    }
  }

  private boolean convertDataTrunk(
    PNGIHDRTrunk ihdrTrunk, byte[] conversionBuffer, int nMaxInflateBuffer)
  throws IOException {
    log.fine("converting colors");

    ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
    for (PNGTrunk dataTrunk : trunks) {
      if (!"IDAT".equalsIgnoreCase(dataTrunk.getName())) continue;
      baos.write(dataTrunk.getData());
    }
    byte[] allData = baos.toByteArray();

    ZStream inflater = new ZStream();
    inflater.avail_in = allData.length;
    inflater.next_in_index = 0;
    inflater.next_in = allData;
    inflater.next_out_index = 0;
    inflater.next_out = conversionBuffer;
    inflater.avail_out = conversionBuffer.length;

    if (inflater.inflateInit(-15) != JZlib.Z_OK) {
      log.fine("PNGCONV_ERR_ZLIB");
      return true;
    }

    int nResult = inflater.inflate(JZlib.Z_NO_FLUSH);
    switch (nResult) {
      case JZlib.Z_NEED_DICT:
        nResult = JZlib.Z_DATA_ERROR;     /* and fall through */
      case JZlib.Z_DATA_ERROR:
      case JZlib.Z_MEM_ERROR:
        inflater.inflateEnd();
        log.fine("PNGCONV_ERR_ZLIB");
        return true;
    }

    nResult = inflater.inflateEnd();

    if (inflater.total_out > nMaxInflateBuffer) {
      log.fine("PNGCONV_ERR_INFLATED_OVER");
    }

    // Switch the color
    int nIndex = 0;
    byte nTemp;
    for (int y = 0; y < ihdrTrunk.m_nHeight; y++) {
      nIndex++;
      for (int x = 0; x < ihdrTrunk.m_nWidth; x++) {
        nTemp = conversionBuffer[nIndex];
        conversionBuffer[nIndex] = conversionBuffer[nIndex + 2];
        conversionBuffer[nIndex + 2] = nTemp;
        nIndex += 4;
      }
    }

    ZStream deflater = new ZStream();
    int nMaxDeflateBuffer = nMaxInflateBuffer + 1024;
    byte[] deBuffer = new byte[nMaxDeflateBuffer];

    deflater.avail_in = (int) inflater.total_out;
    deflater.next_in_index = 0;
    deflater.next_in = conversionBuffer;
    deflater.next_out_index = 0;
    deflater.next_out = deBuffer;
    deflater.avail_out = deBuffer.length;
    deflater.deflateInit(9);
    nResult = deflater.deflate(JZlib.Z_FINISH);


    if (deflater.total_out > nMaxDeflateBuffer) {
      log.fine("PNGCONV_ERR_DEFLATED_OVER");
    }

    byte[] newDeBuffer = new byte[(int) deflater.total_out];
    System.arraycopy(deBuffer, 0, newDeBuffer, 0, newDeBuffer.length);

    PNGTrunk firstDataTrunk = getTrunk("IDAT");
    CRC32 crc32 = new CRC32();
    crc32.update(firstDataTrunk.getName().getBytes());
    crc32.update(newDeBuffer);
    long lCRCValue = crc32.getValue();

    firstDataTrunk.m_nData = newDeBuffer;
    firstDataTrunk.m_nCRC[0] = (byte) ((lCRCValue & 0xFF000000) >> 24);
    firstDataTrunk.m_nCRC[1] = (byte) ((lCRCValue & 0xFF0000) >> 16);
    firstDataTrunk.m_nCRC[2] = (byte) ((lCRCValue & 0xFF00) >> 8);
    firstDataTrunk.m_nCRC[3] = (byte) (lCRCValue & 0xFF);
    firstDataTrunk.m_nSize = newDeBuffer.length;

    return false;
  }

  private void writePng(File newFileName) throws IOException {
    FileOutputStream outStream = new FileOutputStream(newFileName);
    try {
      byte[] pngHeader = {-119, 80, 78, 71, 13, 10, 26, 10};
      outStream.write(pngHeader);
      boolean dataWritten = false;
      for (PNGTrunk trunk : trunks) {
        if (trunk.getName().equalsIgnoreCase("CgBI")) {
          continue;
        }
        if ("IDAT".equalsIgnoreCase(trunk.getName())) {
          if (dataWritten) {
            continue;
          } else {
            dataWritten = true;
          }
        }
        trunk.writeToStream(outStream);
      }
      outStream.flush();

    } finally {
      outStream.close();
    }
  }

  private void readTrunks(File pngFile) throws IOException {
    DataInputStream input = new DataInputStream(new FileInputStream(pngFile));
    try {
      byte[] nPNGHeader = new byte[8];
      input.readFully(nPNGHeader);

      boolean bWithCgBI = false;

      trunks = new ArrayList<PNGTrunk>();
      if ((nPNGHeader[0] == -119) && (nPNGHeader[1] == 0x50) && (nPNGHeader[2] == 0x4e) && (nPNGHeader[3] == 0x47)
        && (nPNGHeader[4] == 0x0d) && (nPNGHeader[5] == 0x0a) && (nPNGHeader[6] == 0x1a) && (nPNGHeader[7] == 0x0a)) {

        PNGTrunk trunk;
        do {
          trunk = PNGTrunk.generateTrunk(input);
          trunks.add(trunk);

          if (trunk.getName().equalsIgnoreCase("CgBI")) {
            bWithCgBI = true;
          }
        }
        while (!trunk.getName().equalsIgnoreCase("IEND"));
      }
    } finally {
      input.close();
    }
  }

  private void convertDirectory(File dir) throws IOException {
    for (File file : dir.listFiles()) {
      convert(file);
    }
  }

  private void convert(File sourceFile) throws IOException {
    if (sourceFile.isDirectory()) {
      convertDirectory(sourceFile);
    } else if (isPngFileName(sourceFile)) {
      File targetFile = getTargetFile(sourceFile);
      log.fine("converting " + sourceFile.getPath() + " --> " + targetFile.getPath());
      convertPngFile(sourceFile, targetFile);
    }
  }

  public static void main(String[] args) throws Exception {
    SimpleFormatter fmt = new SimpleFormatter();
    StreamHandler sh = new StreamHandler(System.out, fmt);
    sh.setLevel(Level.FINE);
    log.setLevel(Level.FINE);
    log.addHandler(sh);

    new IPngConverter(new File(args[0]), new File(args[1])).convert();
  }
}
