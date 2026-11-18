import type { AgeBand, Book } from '../../../types';
import { bookBlockedHint, bookBlockedReason } from '../../../lib/demoPolicy';

interface BookCardProps {
  book: Book;
  ageBand: AgeBand;
  onOpen: (bookId: string) => void;
}

export function BookCard({ book, ageBand, onOpen }: BookCardProps) {
  const blocked = bookBlockedReason(book, ageBand) !== null;

  return (
    <button
      className="catalog-book"
      type="button"
      disabled={blocked}
      onClick={() => onOpen(book.id)}
    >
      <span className="catalog-book__main">
        <span className="catalog-book__title">{book.title}</span>
        <span className="catalog-book__author">{book.author}</span>
      </span>
      <span className="catalog-book__detail">
        {blocked ? bookBlockedHint(book, ageBand) : book.category}
      </span>
    </button>
  );
}
