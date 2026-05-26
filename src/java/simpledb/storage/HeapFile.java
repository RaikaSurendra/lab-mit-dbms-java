package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.DbException;
import simpledb.common.Debug;
import simpledb.common.Permissions;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import java.io.*;
import java.util.*;

/**
 * HeapFile is an implementation of a DbFile that stores a collection of tuples
 * in no particular order. Tuples are stored on pages, each of which is a fixed
 * size, and the file is simply a collection of those pages. HeapFile works
 * closely with HeapPage. The format of HeapPages is described in the HeapPage
 * constructor.
 * 
 * @see HeapPage#HeapPage
 * @author Sam Madden
 */
public class HeapFile implements DbFile {

    /**
     * Constructs a heap file backed by the specified file.
     * 
     * @param f
     *            the file that stores the on-disk backing store for this heap
     *            file.
     */
    private final File file;
    private final TupleDesc td;

    /**
     * Constructs a heap file backed by the specified file.
     * 
     * @param f
     *            the file that stores the on-disk backing store for this heap
     *            file.
     */
    public HeapFile(File f, TupleDesc td) {
        if (f == null || td == null) {
            throw new IllegalArgumentException("File and TupleDesc cannot be null");
        }
        this.file = f;
        this.td = td;
    }

    /**
     * Returns the File backing this HeapFile on disk.
     * 
     * @return the File backing this HeapFile on disk.
     */
    public File getFile() {
        return file;
    }

    /**
     * Returns an ID uniquely identifying this HeapFile. Implementation note:
     * you will need to generate this tableid somewhere to ensure that each
     * HeapFile has a "unique id," and that you always return the same value for
     * a particular HeapFile. We suggest hashing the absolute file name of the
     * file underlying the heapfile, i.e. f.getAbsoluteFile().hashCode().
     * 
     * @return an ID uniquely identifying this HeapFile.
     */
    public int getId() {
        return file.getAbsoluteFile().hashCode();
    }

    /**
     * Returns the TupleDesc of the table stored in this DbFile.
     * 
     * @return TupleDesc of this DbFile.
     */
    public TupleDesc getTupleDesc() {
        return td;
    }

    // see DbFile.java for javadocs
    public Page readPage(PageId pid) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            int pageSize = BufferPool.getPageSize();
            long offset = (long) pid.getPageNumber() * pageSize;
            if (offset + pageSize > file.length()) {
                throw new IllegalArgumentException("Page does not exist in this file");
            }
            byte[] data = new byte[pageSize];
            raf.seek(offset);
            raf.readFully(data);
            return new HeapPage((HeapPageId) pid, data);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read page from disk", e);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    // see DbFile.java for javadocs
    public void writePage(Page page) throws IOException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            int pageSize = BufferPool.getPageSize();
            long offset = (long) page.getId().getPageNumber() * pageSize;
            raf.seek(offset);
            raf.write(page.getPageData());
        } finally {
            if (raf != null) {
                raf.close();
            }
        }
    }

    /**
     * Returns the number of pages in this HeapFile.
     */
    public int numPages() {
        return (int) (file.length() / BufferPool.getPageSize());
    }

    // see DbFile.java for javadocs
    public List<Page> insertTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        for (int i = 0; i < numPages(); i++) {
            HeapPageId pid = new HeapPageId(getId(), i);
            HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pid, Permissions.READ_WRITE);
            if (page.getNumEmptySlots() > 0) {
                page.insertTuple(t);
                page.markDirty(true, tid);
                return Collections.singletonList(page);
            }
        }
        HeapPageId newPid = new HeapPageId(getId(), numPages());
        HeapPage newPage = new HeapPage(newPid, HeapPage.createEmptyPageData());
        newPage.insertTuple(t);
        newPage.markDirty(true, tid);
        writePage(newPage);
        return Collections.singletonList(newPage);
    }

    // see DbFile.java for javadocs
    public ArrayList<Page> deleteTuple(TransactionId tid, Tuple t) throws DbException,
            TransactionAbortedException {
        RecordId rid = t.getRecordId();
        HeapPageId pid = (HeapPageId) rid.getPageId();
        if (pid.getTableId() != getId())
            throw new DbException("Tuple does not belong to this file");
        HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pid, Permissions.READ_WRITE);
        page.deleteTuple(t);
        page.markDirty(true, tid);
        ArrayList<Page> result = new ArrayList<>();
        result.add(page);
        return result;
    }

    // see DbFile.java for javadocs
    public DbFileIterator iterator(TransactionId tid) {
        return new HeapFileIterator(tid);
    }

    private class HeapFileIterator implements DbFileIterator {
        private final TransactionId tid;
        private int currentPageNo;
        private Iterator<Tuple> currentTupleIterator;

        public HeapFileIterator(TransactionId tid) {
            this.tid = tid;
            this.currentPageNo = -1;
            this.currentTupleIterator = null;
        }

        @Override
        public void open() throws DbException, TransactionAbortedException {
            currentPageNo = 0;
            currentTupleIterator = getTupleIterator(currentPageNo);
        }

        private Iterator<Tuple> getTupleIterator(int pageNo) throws DbException, TransactionAbortedException {
            if (pageNo < 0 || pageNo >= numPages()) {
                return null;
            }
            PageId pid = new HeapPageId(getId(), pageNo);
            HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pid, Permissions.READ_ONLY);
            return page.iterator();
        }

        @Override
        public boolean hasNext() throws DbException, TransactionAbortedException {
            if (currentPageNo < 0) {
                return false; // not open
            }
            if (currentTupleIterator == null) {
                return false;
            }
            if (currentTupleIterator.hasNext()) {
                return true;
            }
            // Try to find the next page that has tuples
            int nextPageNo = currentPageNo + 1;
            while (nextPageNo < numPages()) {
                Iterator<Tuple> nextIterator = getTupleIterator(nextPageNo);
                if (nextIterator != null && nextIterator.hasNext()) {
                    return true;
                }
                nextPageNo++;
            }
            return false;
        }

        @Override
        public Tuple next() throws DbException, TransactionAbortedException, NoSuchElementException {
            if (currentPageNo < 0) {
                throw new NoSuchElementException("Iterator is not open");
            }
            if (currentTupleIterator == null) {
                throw new NoSuchElementException("No more elements");
            }
            if (currentTupleIterator.hasNext()) {
                return currentTupleIterator.next();
            }
            // Move to next pages to find a tuple
            currentPageNo++;
            while (currentPageNo < numPages()) {
                currentTupleIterator = getTupleIterator(currentPageNo);
                if (currentTupleIterator != null && currentTupleIterator.hasNext()) {
                    return currentTupleIterator.next();
                }
                currentPageNo++;
            }
            throw new NoSuchElementException("No more elements");
        }

        @Override
        public void rewind() throws DbException, TransactionAbortedException {
            open();
        }

        @Override
        public void close() {
            currentPageNo = -1;
            currentTupleIterator = null;
        }
    }

}

