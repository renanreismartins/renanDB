package org;

import java.io.IO;
import java.io.RandomAccessFile;

import static org.Page.PAGE_SIZE;

public class Main {
    static void main() {
        IO.println(String.format("Hello and welcome!"));

        for (int i = 1; i <= 5; i++) {
            IO.println("i = " + i);
        }
    }
}

// 📝 Represents a single row in our database (50 bytes total)
class Record {
    int id;          // 4 bytes (The search key)
    byte[] payload;  // 46 bytes (The actual row data)
}

// 🌳 Represents the routing structure in RAM
class Node {
    int numKeys;
    boolean isLeaf;
    int[] keys;      // Max 500 keys (2000 bytes)
    int[] pointers;  // Max 501 Page IDs (4008 bytes)
}

// 💾 Represents the raw 8KB block as it exists on the hard drive
class Page {
    static final int PAGE_SIZE = 8192;
    static final int HEADER_SIZE = 5;
    static final int RECORD_SIZE = 50;

    byte[] buffer = new byte[PAGE_SIZE];

    public void insertRecord(Record newRecord) {
        int numKeys = readInt(1);

        // 1. Find the index where this record belongs
        int insertIndex = 0;
        while (insertIndex < numKeys) {
            int currentOffset = (RECORD_SIZE * insertIndex) + HEADER_SIZE;
            int currentId = readInt(currentOffset);
            if (newRecord.id < currentId) {
                break;
            }
            insertIndex++;
        }

        // 2. Shift existing records to the right
        int targetOffset = (RECORD_SIZE * insertIndex) + HEADER_SIZE;
        int endOfDataOffset = (RECORD_SIZE * numKeys) + HEADER_SIZE;
        int bytesToShift = endOfDataOffset - targetOffset;

        if (bytesToShift > 0) {
            System.arraycopy(buffer, targetOffset, buffer, targetOffset + RECORD_SIZE, bytesToShift);
        }

        // 3. Write the new record into the freed space
        writeInt(targetOffset, newRecord.id);
        writeBytes(targetOffset + 4, newRecord.payload); // 4 = key size that was written above

        // 4. Update the header
        writeInt(1, numKeys + 1);
    }

    // Helper methods for raw byte manipulation
    private int readInt(int offset) {
        return ((buffer[offset] & 0xFF) << 24) | ((buffer[offset + 1] & 0xFF) << 16) | ((buffer[offset + 2] & 0xFF) << 8) | ((buffer[offset + 3] & 0xFF));
    }

    private void writeInt(int offset, int value) {
        // java.nio.ByteBuffer.putInt(offset, value)
        buffer[offset] = (byte) (value >> 24);
        buffer[offset + 1] = (byte) (value >> 16);
        buffer[offset + 2] = (byte) (value >> 8);
        buffer[offset + 3] = (byte) (value);
    }

    private void writeBytes(int offset, byte[] data) {
        System.arraycopy(data, 0, this.buffer, offset, data.length);
    }
}

// 🔍 The algorithm to navigate the tree
class BTree {

    DiskManager diskManager;

    public BTree(DiskManager diskManager) {
        this.diskManager = diskManager;
    }

    public Record search(Node currentNode, int target) {
        int i = 0;
        while (i < currentNode.numKeys && currentNode.keys[i] <= target) {
            i++;
        }

        if (currentNode.isLeaf) {
            int dataLocation = currentNode.pointers[i];
            return readRowFromDisk(dataLocation);
        } else {
            int childPageId = currentNode.pointers[i];
            Node childNode = readNodeFromDisk(childPageId);
            return search(childNode, target);
        }
    }

    // Stub methods for disk I/O
    private Record readRowFromDisk(int location) {
        return new Record();
    }

    private Node readNodeFromDisk(int pageId) {
        return new Node();
    }
}

class DiskManager {
    RandomAccessFile file;

    public DiskManager(String fileName) throws Exception {
        this.file = new RandomAccessFile(fileName, "rw"); // Open in read/write mode
    }

    public Page readPage(int pageId) throws Exception {
        Page page = new Page();

        long fileOffset = pageId * PAGE_SIZE;

        file.seek(fileOffset);
        file.readFully(page.buffer);

        return page;
    }

    public void writePage(int pageId, Page page) throws Exception {
        // 1. Calculate exactly where this page belongs in the file
        long fileOffset = pageId * PAGE_SIZE;

        file.seek(fileOffset);
        file.write(page.buffer);
    }

    public int allocatePage() throws Exception {
        long currentFileSizeBytes = file.length();

        int newPageId = Math.toIntExact(currentFileSizeBytes / PAGE_SIZE); //TODO Check toIntExact

        // 3. Create a blank 8KB byte array and write it to the end of the file
        // to physically reserve the space on the hard drive
        file.seek(currentFileSizeBytes);
        file.write(new byte[Page.PAGE_SIZE]);

        return newPageId;
    }
}