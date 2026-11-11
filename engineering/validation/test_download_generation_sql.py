#!/usr/bin/env python3
"""Execute DownloadTaskDao.failGeneration's extracted SQL against SQLite."""

from __future__ import annotations

import re
import sqlite3
import unittest
from pathlib import Path


YUNGET_ROOT = Path(__file__).resolve().parents[2]
DAO_PATH = (
    YUNGET_ROOT
    / "app"
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "yunget"
    / "app"
    / "data"
    / "db"
    / "DownloadTaskDao.kt"
)

FAIL_GENERATION_QUERY_RE = re.compile(
    r'@Query\s*\(\s*"(?P<sql>(?:[^"\\]|\\.)*)"\s*\)\s*'
    r"suspend\s+fun\s+failGeneration\s*\(",
    re.DOTALL,
)

STATUS_DOWNLOADING = 1
STATUS_PAUSED = 2
STATUS_COMPLETED = 3
STATUS_FAILED = 4


def extract_fail_generation_sql() -> str:
    """Extract the original failGeneration @Query string from the Kotlin DAO."""
    source = DAO_PATH.read_text(encoding="utf-8")
    matches = list(FAIL_GENERATION_QUERY_RE.finditer(source))
    if len(matches) != 1:
        raise AssertionError(
            f"Expected one failGeneration @Query in {DAO_PATH}, found {len(matches)}"
        )

    sql = matches[0].group("sql")
    if "\\" in sql:
        raise AssertionError(
            "The failGeneration query now contains Kotlin escapes; update extraction"
        )

    missing = [
        placeholder
        for placeholder in (":id", ":generation", ":errorMsg", ":stage")
        if placeholder not in sql
    ]
    if missing:
        raise AssertionError(f"Extracted query is missing placeholders: {missing}")
    return sql


class FailGenerationSqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fail_generation_sql = extract_fail_generation_sql()

    def setUp(self) -> None:
        self.connection = sqlite3.connect(":memory:")
        self.connection.row_factory = sqlite3.Row
        self.connection.execute(
            """
            CREATE TABLE download_task (
                id INTEGER PRIMARY KEY NOT NULL,
                status INTEGER NOT NULL,
                errorMsg TEXT NOT NULL,
                pipelineStage TEXT NOT NULL,
                attemptGeneration INTEGER NOT NULL,
                completedCachePath TEXT NOT NULL,
                completedCacheSize INTEGER NOT NULL
            )
            """
        )

    def tearDown(self) -> None:
        self.connection.close()

    def _seed(
        self,
        *,
        task_id: int,
        status: int,
        error_msg: str,
        stage: str,
        generation: int,
        cache_path: str = "",
        cache_size: int = 0,
    ) -> None:
        self.connection.execute(
            """
            INSERT INTO download_task (
                id, status, errorMsg, pipelineStage, attemptGeneration,
                completedCachePath, completedCacheSize
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                task_id,
                status,
                error_msg,
                stage,
                generation,
                cache_path,
                cache_size,
            ),
        )

    def _fail(
        self,
        *,
        task_id: int,
        generation: int,
        error_msg: str,
        stage: str | None,
    ) -> int:
        cursor = self.connection.execute(
            self.fail_generation_sql,
            {
                "id": task_id,
                "generation": generation,
                "errorMsg": error_msg,
                "stage": stage,
            },
        )
        return cursor.rowcount

    def _row(self, task_id: int) -> sqlite3.Row:
        row = self.connection.execute(
            """
            SELECT status, errorMsg, pipelineStage, attemptGeneration,
                   completedCachePath, completedCacheSize
            FROM download_task
            WHERE id = ?
            """,
            (task_id,),
        ).fetchone()
        self.assertIsNotNone(row)
        return row

    def test_extracts_query_from_original_dao_source(self) -> None:
        sql = self.fail_generation_sql
        self.assertIn("UPDATE download_task", sql)
        self.assertIn("COALESCE(:stage, pipelineStage)", sql)
        self.assertIn("status = 1", sql)
        self.assertNotIn("?", sql)

    def test_old_generation_cannot_overwrite_new_attempt(self) -> None:
        self._seed(
            task_id=1,
            status=STATUS_DOWNLOADING,
            error_msg="",
            stage="NETWORK",
            generation=20,
            cache_path="/cache/current-attempt.bin",
            cache_size=4097,
        )

        affected = self._fail(
            task_id=1,
            generation=19,
            error_msg="stale generation failure",
            stage="SAVE_FAILED",
        )

        self.assertEqual(affected, 0)
        row = self._row(1)
        self.assertEqual(row["status"], STATUS_DOWNLOADING)
        self.assertEqual(row["errorMsg"], "")
        self.assertEqual(row["pipelineStage"], "NETWORK")
        self.assertEqual(row["attemptGeneration"], 20)
        self.assertEqual(row["completedCachePath"], "/cache/current-attempt.bin")
        self.assertEqual(row["completedCacheSize"], 4097)

    def test_paused_completed_and_failed_rows_reject_failure_update(self) -> None:
        cases = (
            (10, STATUS_PAUSED, "paused marker", "PROCESSING", "/cache/paused.bin", 101),
            (11, STATUS_COMPLETED, "", "SAVED", "/cache/completed.bin", 202),
            (12, STATUS_FAILED, "prior failure", "SAVE_FAILED", "/cache/failed.bin", 303),
        )

        for index, case in enumerate(cases):
            task_id, status, error_msg, stage, cache_path, cache_size = case
            with self.subTest(status=status):
                self._seed(
                    task_id=task_id,
                    status=status,
                    error_msg=error_msg,
                    stage=stage,
                    generation=30 + index,
                    cache_path=cache_path,
                    cache_size=cache_size,
                )

                affected = self._fail(
                    task_id=task_id,
                    generation=30 + index,
                    error_msg="late stale callback",
                    stage="SAVE_FAILED",
                )

                self.assertEqual(affected, 0)
                row = self._row(task_id)
                self.assertEqual(row["status"], status)
                self.assertEqual(row["errorMsg"], error_msg)
                self.assertEqual(row["pipelineStage"], stage)
                self.assertEqual(row["attemptGeneration"], 30 + index)
                self.assertEqual(row["completedCachePath"], cache_path)
                self.assertEqual(row["completedCacheSize"], cache_size)

    def test_downloading_row_fails_and_atomically_sets_save_failed_stage(self) -> None:
        self._seed(
            task_id=20,
            status=STATUS_DOWNLOADING,
            error_msg="",
            stage="SAVING",
            generation=40,
            cache_path="/cache/ready-to-save.bin",
            cache_size=123456,
        )

        affected = self._fail(
            task_id=20,
            generation=40,
            error_msg="final save failed",
            stage="SAVE_FAILED",
        )

        self.assertEqual(affected, 1)
        row = self._row(20)
        self.assertEqual(row["status"], STATUS_FAILED)
        self.assertEqual(row["errorMsg"], "final save failed")
        self.assertEqual(row["pipelineStage"], "SAVE_FAILED")
        self.assertEqual(row["attemptGeneration"], 40)
        self.assertEqual(row["completedCachePath"], "/cache/ready-to-save.bin")
        self.assertEqual(row["completedCacheSize"], 123456)

    def test_null_stage_preserves_processing_stage(self) -> None:
        self._seed(
            task_id=30,
            status=STATUS_DOWNLOADING,
            error_msg="",
            stage="PROCESSING",
            generation=50,
            cache_path="/cache/in-progress.bin",
            cache_size=654321,
        )

        affected = self._fail(
            task_id=30,
            generation=50,
            error_msg="processing failed",
            stage=None,
        )

        self.assertEqual(affected, 1)
        row = self._row(30)
        self.assertEqual(row["status"], STATUS_FAILED)
        self.assertEqual(row["errorMsg"], "processing failed")
        self.assertEqual(row["pipelineStage"], "PROCESSING")
        self.assertEqual(row["attemptGeneration"], 50)
        self.assertEqual(row["completedCachePath"], "/cache/in-progress.bin")
        self.assertEqual(row["completedCacheSize"], 654321)

    def test_completed_cache_fields_are_preserved(self) -> None:
        self._seed(
            task_id=40,
            status=STATUS_DOWNLOADING,
            error_msg="",
            stage="SAVING",
            generation=60,
            cache_path="/cache/completed-cache.bin",
            cache_size=987654321,
        )

        affected = self._fail(
            task_id=40,
            generation=60,
            error_msg="save stage failed",
            stage="SAVE_FAILED",
        )

        self.assertEqual(affected, 1)
        row = self._row(40)
        self.assertEqual(row["completedCachePath"], "/cache/completed-cache.bin")
        self.assertEqual(row["completedCacheSize"], 987654321)


if __name__ == "__main__":
    unittest.main(verbosity=2)
