"""
One-time Firebase cleanup script.
Batch-deletes all documents from the legacy collections:
  - news_articles
  - categories
  - rss_metrics

Run via the cleanup-firebase.yml workflow_dispatch workflow.
NEVER run this script on article_bundles — that is the live collection.
"""

import os
from google.cloud import firestore

LEGACY_COLLECTIONS = ["news_articles", "categories", "rss_metrics"]
BATCH_LIMIT = 400


def delete_collection(db: firestore.Client, collection_name: str):
    """Delete all documents in a collection in batches."""
    col_ref = db.collection(collection_name)
    total = 0

    while True:
        docs = col_ref.limit(BATCH_LIMIT).stream()
        batch = db.batch()
        count = 0

        for doc in docs:
            batch.delete(doc.reference)
            count += 1

        if count == 0:
            break

        batch.commit()
        total += count
        print(f"  Deleted {total} docs from '{collection_name}'...")

    print(f"[OK] '{collection_name}' cleared — {total} total documents deleted")


def main():
    print("=" * 60)
    print("Headlinr — Firebase Legacy Collection Cleanup")
    print("=" * 60)
    print("WARNING: This permanently deletes legacy Firestore data.")
    print()

    db = firestore.Client()

    for col in LEGACY_COLLECTIONS:
        print(f"\nCleaning '{col}'...")
        delete_collection(db, col)

    print("\n" + "=" * 60)
    print("DONE — All legacy collections cleared.")
    print("=" * 60)


if __name__ == "__main__":
    main()
