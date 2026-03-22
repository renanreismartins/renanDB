package org;

import java.io.IO;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

import static org.Page.PAGE_SIZE;

public class Main {
    static void main() throws Exception {
        IO.println(String.format("Hello and welcome!"));

        DiskManager diskManager = new DiskManager("db.db");
        BTree tree = new BTree(diskManager);
        tree.insert(new Record(1, "renan"));
        IO.println(tree.search(1));
    }
}

// 📝 Represents a single row in our database (50 bytes total)
record Record(int id, byte[] payload) {

    public Record(int id, String text) {
        this(id, toPayload(text));
    }

    private static byte[] toPayload(String text) {
        byte[] payload = new byte[46];
        if (text != null) {
            byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
            int lengthToCopy = Math.min(textBytes.length, payload.length);
            System.arraycopy(textBytes, 0, payload, 0, lengthToCopy);
        }
        return payload;
    }

    public String getText() {
        if (this.payload() == null) {
            return null;
        }

        int length = 0;
        while (length < this.payload().length && this.payload()[length] != 0) {
            length++;
        }

        return new String(this.payload(), 0, length, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "Record{" +
                "id=" + id() +
                ", payload=" + getText() +
                '}';
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
    static final int KEY_SIZE = 4;
    static final int PAYLOAD_SIZE = RECORD_SIZE - KEY_SIZE;
    static final int MAX_KEYS = 500;
    static final int POINTERS_OFFSET = (MAX_KEYS * KEY_SIZE) + HEADER_SIZE;

    byte[] buffer = new byte[PAGE_SIZE];

    public void insertRecord(Record newRecord) {
        int numKeys = readInt(1);

        // 1. Find the index where this record belongs
        int insertIndex = 0;
        while (insertIndex < numKeys) {
            int currentOffset = (RECORD_SIZE * insertIndex) + HEADER_SIZE;
            int currentId = readInt(currentOffset);
            if (newRecord.id() < currentId) {
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
        writeInt(targetOffset, newRecord.id());
        writeBytes(targetOffset + KEY_SIZE, newRecord.payload());

        // 4. Update the header
        writeInt(1, numKeys + 1);
    }

    private int readInt(int offset) {
        return ((buffer[offset] & 0xFF) << 24)
        | ((buffer[offset + 1] & 0xFF) << 16)
        | ((buffer[offset + 2] & 0xFF) << 8)
        | ((buffer[offset + 3] & 0xFF));
    }

    public Record readRecord(int offset) {
        return new Record(readInt(offset), readBytes(offset + KEY_SIZE, PAYLOAD_SIZE));
    }

    public boolean matchesKey(int offset, int key) {
        return readInt(offset) == key;
    }

    private byte[] readBytes(int offset, int length) {
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

    public void setLeaf(boolean leaf) {
        buffer[0] = leaf ? (byte) 1 : (byte) 0;
    }

    private void writeBytes(int offset, byte[] data) {
        System.arraycopy(data, 0, this.buffer, offset, data.length);
    }

    public int numKeys() {
        return readInt(1);
    }

    public Node toNode() {
        Node node = new Node();
        node.isLeaf = buffer[0] == 1;
        node.numKeys = numKeys();

        node.keys = new int[MAX_KEYS];
        node.pointers = new int[MAX_KEYS + 1];

        for (int i = 0; i < node.numKeys; i++) {
            int keyOffset = (i * KEY_SIZE) + HEADER_SIZE;
            node.keys[i] = readInt(keyOffset);
        }

        for (int i = 0; i <= node.numKeys; i++) {
            int pointerOffset = (i * KEY_SIZE) + POINTERS_OFFSET;
            node.pointers[i] = readInt(pointerOffset);
        }

        return node;
    }
}

// 🔍 The algorithm to navigate the tree
class BTree {
    DiskManager diskManager;
    int rootPageId = -1;

    public BTree(DiskManager diskManager) {
        this.diskManager = diskManager;
    }

    public void insert(Record record) throws Exception {
        if (rootPageId == -1) {
            int pageId = diskManager.allocatePage();
            this.rootPageId = pageId;
            Page page = diskManager.readPage(pageId);
            page.setLeaf(true);
            page.insertRecord(record);
            diskManager.writePage(pageId, page);
        } else {
            //TODO If rootPageId is no longer -1, it means our tree already exists on the disk.
            // Before we can figure out where this new record belongs,
            // what is the very first method call we need to make to bring our existing tree into memory?

        }

    }

    public Record search(int target) throws Exception {
        Node rootNode = readNodeFromDisk(this.rootPageId);
        return search(rootNode, target);
    }

    private Record search(Node currentNode, int target) throws Exception {
        int i = (int) IntStream.range(0, currentNode.numKeys)
                .filter(idx -> currentNode.keys[idx] <= target)
                .count();

        if (currentNode.isLeaf) {
            int dataLocation = currentNode.pointers[i];
            return readRowFromDisk(dataLocation, target);
        } else {
            int childPageId = currentNode.pointers[i];
            Node childNode = readNodeFromDisk(childPageId);
            return search(childNode, target);
        }
    }

    private Record readRowFromDisk(int pageId, int searchKey) throws Exception {
        Page page = diskManager.readPage(pageId);
        int numRecords = page.numKeys();

        return IntStream.range(0, numRecords)
                .mapToObj(i -> (i * Page.RECORD_SIZE) + Page.HEADER_SIZE)
                .filter(offset -> page.matchesKey(offset, searchKey))
                .findFirst()
                .map(page::readRecord)
                .orElse(null);
    }

    private Node readNodeFromDisk(int pageId) throws Exception {
        Page page = diskManager.readPage(pageId);
        return page.toNode();
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