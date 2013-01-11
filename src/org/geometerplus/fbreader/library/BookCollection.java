/*
 * Copyright (C) 2007-2013 Geometer Plus <contact@geometerplus.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.fbreader.library;

import java.io.File;
import java.util.*;

import org.geometerplus.zlibrary.core.filesystem.ZLFile;
import org.geometerplus.zlibrary.core.filesystem.ZLPhysicalFile;

import org.geometerplus.fbreader.Paths;
import org.geometerplus.fbreader.bookmodel.BookReadingException;

public class BookCollection implements IBookCollection {
	private final List<Listener> myListeners = Collections.synchronizedList(new LinkedList<Listener>());

	public interface Listener {
		public enum BookEvent {
			Added,
			Updated,
			Removed
		}

		public enum BuildEvent {
			Started,
			NotStarted,
			Succeeded,
			Failed,
			Completed
		}

		void onBookEvent(BookEvent event, Book book);
		void onBuildEvent(BuildEvent event);
	}

	public void addListener(Listener listener) {
		myListeners.add(listener);
	}

	public void removeListener(Listener listener) {
		myListeners.remove(listener);
	}

	protected void fireBookEvent(Listener.BookEvent event, Book book) {
		synchronized (myListeners) {
			for (Listener l : myListeners) {
				l.onBookEvent(event, book);
			}
		}
	}
	protected void fireBuildEvent(Listener.BuildEvent event) {
		synchronized (myListeners) {
			for (Listener l : myListeners) {
				l.onBuildEvent(event);
			}
		}
	}
	private final BooksDatabase myDatabase;
	private final Map<ZLFile,Book> myBooksByFile =
		Collections.synchronizedMap(new LinkedHashMap<ZLFile,Book>());
	private final Map<Long,Book> myBooksById =
		Collections.synchronizedMap(new HashMap<Long,Book>());
	private volatile boolean myBuildStarted = false;

	public BookCollection(BooksDatabase db) {
		myDatabase = db;
	}

	public int size() {
		return myBooksByFile.size();
	}

	public Book getBookById(long id) {
		Book book = myBooksById.get(id);
		if (book != null) {
			return book;
		}

		book = BooksDatabase.Instance().loadBook(id);
		if (book == null) {
			return null;
		}
		book.loadLists();

		final ZLFile bookFile = book.File;
		final ZLPhysicalFile physicalFile = bookFile.getPhysicalFile();
		if (physicalFile == null) {
			addBook(book);
			return book;
		}
		if (!physicalFile.exists()) {
			return null;
		}

		FileInfoSet fileInfos = new FileInfoSet(physicalFile);
		if (fileInfos.check(physicalFile, physicalFile != bookFile)) {
			addBook(book);
			return book;
		}
		fileInfos.save();

		try {
			book.readMetaInfo();
			addBook(book);
			return book;
		} catch (BookReadingException e) {
			return null;
		}
	}

	private void addBook(Book book) {
		synchronized (myBooksByFile) {
			if (book == null || myBooksByFile.containsKey(book.File)) {
				return;
			}
			myBooksByFile.put(book.File, book);
			addBookById(book);
		}
		fireBookEvent(Listener.BookEvent.Added, book);
	}

	private void addBookById(Book book) {
		final long id = book.getId();
		if (id != -1) {
			myBooksById.put(id, book);
		}
	}

	public void removeBook(Book book, boolean deleteFromDisk) {
		synchronized (myBooksByFile) {
			myBooksByFile.remove(book.File);
			myBooksById.remove(book.getId());
			final List<Long> ids = myDatabase.loadRecentBookIds();
			if (ids.remove(book.getId())) {
				myDatabase.saveRecentBookIds(ids);
			}
			myDatabase.deleteFromBookList(book.getId());
			if (deleteFromDisk) {
				book.File.getPhysicalFile().delete();
			}
		}
		fireBookEvent(Listener.BookEvent.Removed, book);
	}

	public List<Book> books() {
		synchronized (myBooksByFile) {
			return new ArrayList<Book>(myBooksByFile.values());
		}
	}

	public List<Book> recentBooks() {
		return books(myDatabase.loadRecentBookIds());
	}

	public List<Book> favorites() {
		return books(myDatabase.loadFavoriteIds());
	}

	private List<Book> books(List<Long> ids) {
		final List<Book> bookList = new ArrayList<Book>(ids.size());
		for (long id : ids) {
			final Book book = getBookById(id);
			if (book != null) {
				bookList.add(book);
			}
		}
		return bookList;
	}

	public Book getRecentBook(int index) {
		List<Long> recentIds = myDatabase.loadRecentBookIds();
		return recentIds.size() > index ? getBookById(recentIds.get(index)) : null;
	}

	public void addBookToRecentList(Book book) {
		final List<Long> ids = myDatabase.loadRecentBookIds();
		final Long bookId = book.getId();
		ids.remove(bookId);
		ids.add(0, bookId);
		if (ids.size() > 12) {
			ids.remove(12);
		}
		myDatabase.saveRecentBookIds(ids);
	}

	public void setBookFavorite(Book book, boolean favorite) {
		if (favorite) {
			myDatabase.addToFavorites(book.getId());
		} else {
			myDatabase.removeFromFavorites(book.getId());
		}
		fireBookEvent(Listener.BookEvent.Updated, book);
	}

	public synchronized void startBuild() {
		if (myBuildStarted) {
			fireBuildEvent(Listener.BuildEvent.NotStarted);
			return;
		}
		myBuildStarted = true;

		final Thread builder = new Thread("Library.build") {
			public void run() {
				try {
					fireBuildEvent(Listener.BuildEvent.Started);
					build();
					fireBuildEvent(Listener.BuildEvent.Succeeded);
				} catch (Throwable t) {
					fireBuildEvent(Listener.BuildEvent.Failed);
				} finally {
					fireBuildEvent(Listener.BuildEvent.Completed);
					myBuildStarted = false;
				}
			}
		};
		builder.setPriority((Thread.MIN_PRIORITY + Thread.NORM_PRIORITY) / 2);
		builder.start();
	}

	private void build() {
		// Step 0: get database books marked as "existing"
		final FileInfoSet fileInfos = new FileInfoSet();
		final Map<Long,Book> savedBooksByFileId = myDatabase.loadBooks(fileInfos, true);
		final Map<Long,Book> savedBooksByBookId = new HashMap<Long,Book>();
		for (Book b : savedBooksByFileId.values()) {
			savedBooksByBookId.put(b.getId(), b);
		}

		// Step 1: set myDoGroupTitlesByFirstLetter value
		//if (savedBooksByFileId.size() > 10) {
		//	final HashSet<String> letterSet = new HashSet<String>();
		//	for (Book book : savedBooksByFileId.values()) {
		//		final String letter = TitleTree.firstTitleLetter(book);
		//		if (letter != null) {
		//			letterSet.add(letter);
		//		}
		//	}
		//	myDoGroupTitlesByFirstLetter = savedBooksByFileId.values().size() > letterSet.size() * 5 / 4;
		//}

		// Step 2: check if files corresponding to "existing" books really exists;
		//         add books to library if yes (and reload book info if needed);
		//         remove from recent/favorites list if no;
		//         collect newly "orphaned" books
		final Set<Book> orphanedBooks = new HashSet<Book>();
		final Set<ZLPhysicalFile> physicalFiles = new HashSet<ZLPhysicalFile>();
		int count = 0;
		for (Book book : savedBooksByFileId.values()) {
			synchronized (this) {
				final ZLPhysicalFile file = book.File.getPhysicalFile();
				if (file != null) {
					physicalFiles.add(file);
				}
				if (file != book.File && file != null && file.getPath().endsWith(".epub")) {
					myDatabase.deleteFromBookList(book.getId());
					continue;
				}
				if (book.File.exists()) {
					boolean doAdd = true;
					if (file == null) {
						continue;
					}
					if (!fileInfos.check(file, true)) {
						try {
							book.readMetaInfo();
							book.save();
						} catch (BookReadingException e) {
							doAdd = false;
						}
						file.setCached(false);
					}
					if (doAdd) {
						addBook(book);
					}
				} else {
					//myRootTree.removeBook(book, true);
					//fireBookEvent(Listener.BookEvent.Removed);
					orphanedBooks.add(book);
				}
			}
		}
		myDatabase.setExistingFlag(orphanedBooks, false);

		// Step 3: collect books from physical files; add new, update already added,
		//         unmark orphaned as existing again, collect newly added
		final Map<Long,Book> orphanedBooksByFileId = myDatabase.loadBooks(fileInfos, false);
		final Set<Book> newBooks = new HashSet<Book>();

		final List<ZLPhysicalFile> physicalFilesList = collectPhysicalFiles();
		for (ZLPhysicalFile file : physicalFilesList) {
			if (physicalFiles.contains(file)) {
				continue;
			}
			collectBooks(
				file, fileInfos,
				savedBooksByFileId, orphanedBooksByFileId,
				newBooks,
				!fileInfos.check(file, true)
			);
			file.setCached(false);
		}
		
		// Step 4: add help file
		try {
			final ZLFile helpFile = Library.getHelpFile();
			Book helpBook = savedBooksByFileId.get(fileInfos.getId(helpFile));
			if (helpBook == null) {
				helpBook = new Book(helpFile);
			}
			addBook(helpBook);
		} catch (BookReadingException e) {
			// that's impossible
			e.printStackTrace();
		}

		// Step 5: save changes into database
		fileInfos.save();

		myDatabase.executeAsATransaction(new Runnable() {
			public void run() {
				for (Book book : newBooks) {
					book.save();
					addBookById(book);
				}
			}
		});
		myDatabase.setExistingFlag(newBooks, true);
	}

	private List<ZLPhysicalFile> collectPhysicalFiles() {
		final Queue<ZLFile> dirQueue = new LinkedList<ZLFile>();
		final HashSet<ZLFile> dirSet = new HashSet<ZLFile>();
		final LinkedList<ZLPhysicalFile> fileList = new LinkedList<ZLPhysicalFile>();

		dirQueue.offer(new ZLPhysicalFile(new File(Paths.BooksDirectoryOption().getValue())));

		while (!dirQueue.isEmpty()) {
			for (ZLFile file : dirQueue.poll().children()) {
				if (file.isDirectory()) {
					if (!dirSet.contains(file)) {
						dirQueue.add(file);
						dirSet.add(file);
					}
				} else {
					file.setCached(true);
					fileList.add((ZLPhysicalFile)file);
				}
			}
		}
		return fileList;
	}

	private void collectBooks(
		ZLFile file, FileInfoSet fileInfos,
		Map<Long,Book> savedBooksByFileId, Map<Long,Book> orphanedBooksByFileId,
		Set<Book> newBooks,
		boolean doReadMetaInfo
	) {
		final long fileId = fileInfos.getId(file);
		if (savedBooksByFileId.get(fileId) != null) {
			return;
		}

		try {
			final Book book = orphanedBooksByFileId.get(fileId);
			if (book != null) {
				if (doReadMetaInfo) {
					book.readMetaInfo();
				}
				addBook(book);
				newBooks.add(book);
				return;
			}
		} catch (BookReadingException e) {
			// ignore
		}

		try {
			final Book book = new Book(file);
			addBook(book);
			newBooks.add(book);
			return;
		} catch (BookReadingException e) {
			// ignore
		}

		if (file.isArchive()) {
			for (ZLFile entry : fileInfos.archiveEntries(file)) {
				collectBooks(
					entry, fileInfos,
					savedBooksByFileId, orphanedBooksByFileId,
					newBooks,
					doReadMetaInfo
				);
			}
		}
	}

	public List<Bookmark> allBookmarks() {
		return myDatabase.loadAllVisibleBookmarks();
	}

	public List<Bookmark> invisibleBookmarks(Book book) {
		final List<Bookmark> list = myDatabase.loadBookmarks(book.getId(), false);
		Collections.sort(list, new Bookmark.ByTimeComparator());
		return list;
	}

	public void saveBookmark(Bookmark bookmark) {
		if (bookmark != null) {
			bookmark.setId(BooksDatabase.Instance().saveBookmark(bookmark));
		}
	}

	public void deleteBookmark(Bookmark bookmark) {
		if (bookmark != null && bookmark.getId() != -1) {
			BooksDatabase.Instance().deleteBookmark(bookmark);
		}
	}
}
