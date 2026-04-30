// Web Reader Bookmark and Note Functionality

(function() {
    'use strict';

    // Current book info (to be set by the Vue app)
    let currentBookName = '';
    let currentBookAuthor = '';
    let currentChapterIndex = 0;
    let bookmarks = [];
    let notes = [];

    // Floating menu element
    let floatingMenu = null;
    let currentSelection = null;

    // Initialize the bookmark system
    function init() {
        createFloatingMenu();
        setupSelectionListeners();
        loadBookmarksAndNotes();

        // Listen for book info updates from Vue app
        window.addEventListener('bookInfoUpdate', function(e) {
            const data = e.detail;
            currentBookName = data.bookName || '';
            currentBookAuthor = data.bookAuthor || '';
            currentChapterIndex = data.chapterIndex || 0;
            loadBookmarksAndNotes();
            applyBookmarkHighlights();
        });
    }

    // Create floating menu
    function createFloatingMenu() {
        floatingMenu = document.createElement('div');
        floatingMenu.id = 'bookmark-menu';
        floatingMenu.className = 'bookmark-menu';
        floatingMenu.style.cssText = `
            position: fixed;
            display: none;
            background: #2196F3;
            border-radius: 8px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.3);
            z-index: 9999;
            padding: 8px;
        `;

        const addBookmarkBtn = createMenuItem('书签', '#FFA500');
        const addNoteBtn = createMenuItem('笔记', '#4CAF50');

        floatingMenu.appendChild(addBookmarkBtn);
        floatingMenu.appendChild(document.createTextNode(' '));
        floatingMenu.appendChild(addNoteBtn);

        addBookmarkBtn.addEventListener('click', () => {
            addBookmark();
            hideMenu();
        });

        addNoteBtn.addEventListener('click', () => {
            addNote();
            hideMenu();
        });

        document.body.appendChild(floatingMenu);

        // Close menu when clicking outside
        document.addEventListener('click', function(e) {
            if (!floatingMenu.contains(e.target)) {
                hideMenu();
            }
        });
    }

    function createMenuItem(text, color) {
        const btn = document.createElement('button');
        btn.textContent = text;
        btn.style.cssText = `
            background: ${color};
            color: white;
            border: none;
            border-radius: 4px;
            padding: 6px 12px;
            cursor: pointer;
            font-size: 14px;
        `;
        return btn;
    }

    // Setup selection listeners
    function setupSelectionListeners() {
        document.addEventListener('mouseup', function(e) {
            const selection = window.getSelection();
            if (selection.toString().trim().length > 0) {
                currentSelection = selection.toString();
                showMenu(e.clientX, e.clientY);
            } else {
                hideMenu();
            }
        });
    }

    function showMenu(x, y) {
        floatingMenu.style.display = 'block';
        floatingMenu.style.left = (x - 50) + 'px';
        floatingMenu.style.top = (y - 50) + 'px';
    }

    function hideMenu() {
        floatingMenu.style.display = 'none';
    }

    // Load bookmarks and notes from server
    function loadBookmarksAndNotes() {
        if (!currentBookName || !currentBookAuthor) return;

        // Load bookmarks
        fetch('/getBookmarks?bookName=' + encodeURIComponent(currentBookName) +
              '&bookAuthor=' + encodeURIComponent(currentBookAuthor))
            .then(res => res.json())
            .then(data => {
                if (data.data) {
                    bookmarks = data.data;
                    applyBookmarkHighlights();
                }
            })
            .catch(err => console.error('Failed to load bookmarks:', err));

        // Load notes
        fetch('/getNotes?bookName=' + encodeURIComponent(currentBookName) +
              '&bookAuthor=' + encodeURIComponent(currentBookAuthor))
            .then(res => res.json())
            .then(data => {
                if (data.data) {
                    notes = data.data;
                }
            })
            .catch(err => console.error('Failed to load notes:', err));
    }

    // Apply bookmark highlights to the page
    function applyBookmarkHighlights() {
        // Remove existing highlights
        document.querySelectorAll('.bookmark-highlight').forEach(el => {
            const parent = el.parentNode;
            parent.replaceChild(document.createTextNode(el.textContent), el);
            parent.normalize();
        });

        // Apply new highlights for current chapter
        const chapterBookmarks = bookmarks.filter(b => b.chapterIndex === currentChapterIndex);
        chapterBookmarks.forEach(bookmark => {
            highlightText(bookmark.bookText);
        });
    }

    function highlightText(text) {
        if (!text) return;

        const walker = document.createTreeWalker(
            document.body,
            NodeFilter.SHOW_TEXT,
            null,
            false
        );

        let node;
        const nodesToProcess = [];

        while (node = walker.nextNode()) {
            if (node.textContent.includes(text)) {
                nodesToProcess.push(node);
            }
        }

        nodesToProcess.forEach(node => {
            const range = document.createRange();
            const span = document.createElement('span');
            span.className = 'bookmark-highlight';
            span.style.cssText = `
                border-bottom: 3px solid #FFA500;
                padding-bottom: 2px;
                background: rgba(255, 165, 0, 0.1);
            `;

            const index = node.textContent.indexOf(text);
            if (index !== -1) {
                range.setStart(node, index);
                range.setEnd(node, index + text.length);
                range.surroundContents(span);
            }
        });
    }

    // Add bookmark
    function addBookmark() {
        if (!currentSelection || !currentBookName || !currentBookAuthor) {
            alert('请先选择文本');
            return;
        }

        const bookmark = {
            time: Date.now(),
            bookName: currentBookName,
            bookAuthor: currentBookAuthor,
            chapterIndex: currentChapterIndex,
            chapterPos: 0, // Will be calculated by the app
            chapterName: '', // Will be filled by the app
            bookText: currentSelection,
            content: ''
        };

        fetch('/saveBookmark', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(bookmark)
        })
        .then(res => res.json())
        .then(data => {
            if (data.isSuccess) {
                bookmarks.push(data.data);
                applyBookmarkHighlights();
                alert('书签已保存');
            } else {
                alert('保存失败: ' + (data.errorMsg || '未知错误'));
            }
        })
        .catch(err => {
            console.error('Failed to save bookmark:', err);
            alert('保存书签失败');
        });
    }

    // Add note
    function addNote() {
        if (!currentSelection || !currentBookName || !currentBookAuthor) {
            alert('请先选择文本');
            return;
        }

        const noteContent = prompt('请输入笔记内容:', currentSelection);
        if (noteContent === null) return; // Cancelled

        const note = {
            noteId: crypto.randomUUID ? crypto.randomUUID() : 'note_' + Date.now(),
            bookName: currentBookName,
            bookAuthor: currentBookAuthor,
            chapterIndex: currentChapterIndex,
            chapterPos: 0,
            chapterName: '',
            selectedText: currentSelection,
            noteContent: noteContent,
            createdTime: Date.now(),
            updatedTime: Date.now(),
            isSynced: false,
            deleted: false
        };

        fetch('/saveNote', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(note)
        })
        .then(res => res.json())
        .then(data => {
            if (data.isSuccess) {
                notes.push(data.data);
                alert('笔记已保存');
            } else {
                alert('保存失败: ' + (data.errorMsg || '未知错误'));
            }
        })
        .catch(err => {
            console.error('Failed to save note:', err);
            alert('保存笔记失败');
        });
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // Expose methods for external use
    window.BookmarkSystem = {
        loadBookmarksAndNotes: loadBookmarksAndNotes,
        applyBookmarkHighlights: applyBookmarkHighlights,
        setBookInfo: function(bookName, bookAuthor, chapterIndex) {
            currentBookName = bookName;
            currentBookAuthor = bookAuthor;
            currentChapterIndex = chapterIndex || 0;
            loadBookmarksAndNotes();
            applyBookmarkHighlights();
        }
    };

})();