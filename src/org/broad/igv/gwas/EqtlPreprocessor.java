package org.broad.igv.gwas;

import org.broad.igv.tdf.BufferedByteWriter;
import org.broad.igv.tdf.TDFReader;
import org.broad.igv.track.TrackType;
import org.broad.igv.track.WindowFunction;
import org.broad.igv.util.CompressionUtils;

import java.io.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Class for experimenting with binary EQTL formats.
 * <p/>
 * SNP	SNP_Chr	SNP_Pos	Gen_ID	Gene_Name	Gene_Pos	T_Stat	P_Val	Q_Val
 * rs3094317	1	739528	ENSG00000237491.2	RP11-206L10.6	714162	-4.41310870534187	3.457968769964e-05	0.0254439762182497
 */
public class EqtlPreprocessor {

    static int version = 0;

    long indexPositionPosition;
    int bytesWritten;
    BufferedByteWriter currentChrBuffer;
    String currentChr;
    private FileOutputStream fos;
    private Map<String, IndexEntry> chrPositionMap;
    private CompressionUtils compressionUtils;


    static class IndexEntry {

        IndexEntry(long position, int size) {
            this.position = position;
            this.size = size;
        }

        long position;
        int size;
    }

    public static void main(String[] args) throws IOException {

        File[] files = (new File(args[0])).listFiles();
        for (File file : files) {
            if (file.getName().endsWith(".eqtl")) {
                (new EqtlPreprocessor()).process(file.getAbsolutePath(), file.getAbsolutePath() + ".bin");
            }
        }
    }

    public void process(String inputFile, String outputFile) throws IOException {

        bytesWritten = 0;
        BufferedReader br = null;
        fos = null;
        currentChr = null;
        currentChrBuffer = null;
        chrPositionMap = new HashMap<String, IndexEntry>();
        compressionUtils = new CompressionUtils();
        EQTLCodec codec = new EQTLCodec(null);

        try {
            fos = new FileOutputStream(outputFile);
            writeHeader();

            br = new BufferedReader(new FileReader(inputFile));
            String nextLine = br.readLine();

            while ((nextLine = br.readLine()) != null) {

                EQTLFeature feature = codec.decode(nextLine);
                String chr = feature.getChr();
                if (!chr.equals(currentChr)) {
                    if (currentChrBuffer != null) {
                        System.out.println(currentChr);
                        writeChrBuffer();
                    }
                    currentChr = chr;
                    currentChrBuffer = new BufferedByteWriter();
                }

                currentChrBuffer.put(feature.encodeBinary());

            }
            if (currentChrBuffer != null) {
                writeChrBuffer();
            }

            long position = bytesWritten;
            int nBytes = writeIndex();
            fos.close();

            writeIndexPosition(outputFile, position, nBytes);

        } finally {
            if (br != null) br.close();
        }

    }

    private void writeChrBuffer() throws IOException {
        // compress and write
        byte[] rawBytes = currentChrBuffer.getBytes();
        byte[] compressedBytes = rawBytes; //compressionUtils.compress(rawBytes);
        int position = bytesWritten;
        int size = compressedBytes.length;
        write(compressedBytes);
        chrPositionMap.put(currentChr, new IndexEntry(position, size));

    }

    private int writeIndex() throws IOException {
        // compress and write

        BufferedByteWriter buff = new BufferedByteWriter();
        buff.putInt(chrPositionMap.size());
        for (Map.Entry<String, IndexEntry> entry : chrPositionMap.entrySet()) {
            IndexEntry ie = entry.getValue();
            buff.putNullTerminatedString(entry.getKey());
            buff.putLong(ie.position);
            buff.putInt(ie.size);
        }

        byte[] bytes = buff.getBytes();
        write(bytes);
        return bytes.length;

    }

    private void writeHeader() throws IOException {

        // Magic number -- 4 bytes
        byte[] magicNumber = new byte[]{'E', 'Q', 'T', 'L'};

        BufferedByteWriter buffer = new BufferedByteWriter();
        buffer.put(magicNumber);
        buffer.putInt(version);
        // Reserve space for the master index pointer and byte count.
        // The actual values will be written at the end
        indexPositionPosition = buffer.bytesWritten();
        buffer.putLong(0l);   // File position for start of index
        buffer.putInt(0);     // Size in bytes of index

        final byte[] bytes = buffer.getBytes();
        write(bytes);
    }

    private void writeIndexPosition(String file, long indexPosition, int nbytes) {
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            raf.getChannel().position(indexPositionPosition);

            // Write as little endian
            BufferedByteWriter buffer = new BufferedByteWriter();
            buffer.putLong(indexPosition);
            buffer.putInt(nbytes);
            raf.write(buffer.getBytes());
            raf.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }


    private void write(byte[] bytes) throws IOException {
        fos.write(bytes);
        bytesWritten += bytes.length;
    }

}
