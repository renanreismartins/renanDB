package org;

import java.io.IO;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import static org.Page.PAGE_SIZE;

public class Main {
    static void main() throws Exception {
        IO.println(String.format("Hello and welcome!"));


        DiskManager diskManager = new DiskManager("db.db");
        int pageId = diskManager.allocatePage();
        Page page = new Page();
        diskManager.writePage(pageId, page);
        page.insertRecord(new Record(1, "renan"));


        BTree tree = new BTree(diskManager);
        Record record = tree.search(1);
        IO.println(record.getText());
    }
}

// 📝 Represents a single row in our database (50 bytes total)
class Record {
    int id;          // 4 bytes (The search key)
    byte[] payload;  // 46 bytes (The actual row data)

    public Record(int id, String text) {
        this.id = id;

        this.payload = new byte[46];

        if (text != null) {
            byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
            int lengthToCopy = Math.min(textBytes.length, this.payload.length);
            System.arraycopy(textBytes, 0, this.payload, 0, lengthToCopy);
        }
    }

    public Record(int id, byte[] payload) {
        this.id = id;
        this.payload = payload;
    }

    public String getText() {
        if (this.payload == null) {
            return null;
        }

        int length = 0;
        while (length < this.payload.length && this.payload[length] != 0) {
            length++;
        }

        return new String(this.payload, 0, length, StandardCharsets.UTF_8);
    }
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
    public int readInt(int offset) {
        return ((buffer[offset] & 0xFF) << 24) | ((buffer[offset + 1] & 0xFF) << 16) | ((buffer[offset + 2] & 0xFF) << 8) | ((buffer[offset + 3] & 0xFF));
    }

    public byte[] readBytes(int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(this.buffer, offset, result, 0, length);

        return result;
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

    public int numKeys() {
        return readInt(1);
    }
}

// 🔍 The algorithm to navigate the tree
class BTree {

    DiskManager diskManager;

    public BTree(DiskManager diskManager) {
        this.diskManager = diskManager;
    }

    public Record search(int target) throws Exception {
        Node rootNode = readNodeFromDisk(1);
        return search(rootNode, target);
    }

    private Record search(Node currentNode, int target) throws Exception {
        int i = 0;
        while (i < currentNode.numKeys && currentNode.keys[i] <= target) {
            i++;
        }

        if (currentNode.isLeaf) {
            int dataLocation = currentNode.pointers[i];
            return readRowFromDisk(dataLocation, target);
        } else {
            int childPageId = currentNode.pointers[i];
            Node childNode = readNodeFromDisk(childPageId);
            return search(childNode, target);
        }
    }

    // Stub methods for disk I/O
    private Record readRowFromDisk(int pageId, int searchKey) throws Exception {
        Page page = diskManager.readPage(pageId);
        int numRecords = page.numKeys();

        for (int i = 0; i < numRecords; i++) {
            int currentOffset = (i * 50) + 5;

            // 1. Read the ID at this offset
            int currentId = page.readInt(currentOffset);

            // 2. Check if it matches our searchKey
            if (currentId == searchKey) {
                byte[] payload = page.readBytes(currentOffset, 46);

                return new Record(currentId, payload);
            }
        }

        return null; // Record not found on this page
    }

    private Node readNodeFromDisk(int pageId) throws Exception {
        Page page = diskManager.readPage(pageId);
        Node node = new Node();
        node.isLeaf = page.buffer[0] == 1;
        node.numKeys = page.numKeys();

        node.keys = new int[500];
        node.pointers = new int[501];

        for (int i = 0; i < node.numKeys; i++) {
            int keyOffset = (i * 4) + 5;
            //TODO method on the page tha returns this array, moving the for loop to the page
            node.keys[i] = page.readInt(keyOffset);
        }

        for (int i = 0; i <= node.numKeys; i++) {
            int pointerOffset = (i * 4) + 2005;
            node.pointers[i] = page.readInt(pointerOffset);
        }

        return node;
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