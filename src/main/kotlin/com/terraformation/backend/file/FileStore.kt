package com.terraformation.backend.file

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant

/**
 * Handles storage of file contents (as opposed to metadata). Implementations of this interface talk
 * to specific storage back ends such as S3 or the local filesystem. This is used to store arbitrary
 * binary files such as photos.
 *
 * This does not deal with thumbnails or photo metadata; it is just an interface to a file storage
 * system.
 *
 * Callers can declare a dependency on this interface; exactly one of the implementations will be
 * used depending on server configuration settings.
 */
interface FileStore {
  /**
   * Deletes a file from the storage system. If this returns successfully, the file was deleted.
   *
   * @throws NoSuchFileException The file did not exist.
   * @throws IOException An error occurred while deleting the file. The file may or may not have
   *   actually been removed.
   * @throws InvalidStorageLocationException The URL referred to a file that isn't managed by this
   *   file store.
   */
  fun delete(url: URI)

  /**
   * Reads a file from the storage system. The returned stream is not guaranteed to be non-blocking.
   *
   * @return An input stream that includes the file size. The caller is responsible for closing it.
   * @throws NoSuchFileException The file does not exist.
   * @throws IOException An error occurred while opening the file.
   * @throws InvalidStorageLocationException The URL referred to a file that isn't managed by this
   *   file store.
   */
  fun read(url: URI): SizedInputStream

  /**
   * Returns the size of a file. Note that [read] returns the size alongside the file contents, so
   * if you are reading the file anyway, you don't need to call this method.
   *
   * @throws NoSuchFileException The file does not exist.
   * @throws IOException An error occurred while fetching the file's size.
   * @throws InvalidStorageLocationException The URL referred to a file that isn't managed by this
   *   file store.
   */
  fun size(url: URI): Long

  /**
   * Writes a file to the storage system. Does not overwrite existing data. If you need to overwrite
   * an existing file, call [delete] first. If this returns successfully, the file was written.
   *
   * @param contents File contents to copy to the storage system. When this method returns
   *   successfully, this stream will have been completely consumed. The caller is responsible for
   *   closing this.
   * @throws FileAlreadyExistsException The file already existed.
   * @throws IOException An error occurred while writing the file or while reading [contents].
   *   Implementations should attempt to delete files that weren't written successfully, though
   *   depending on the nature of the error, it may be impossible to do so.
   * @throws InvalidStorageLocationException The URL referred to a file that isn't managed by this
   *   file store.
   */
  fun write(url: URI, contents: InputStream)

  /** Returns true if this file store can accept a URI. */
  fun canAccept(url: URI): Boolean

  /** Returns the URL of a file with a given relative path on this file store. */
  fun getUrl(path: Path): URI

  /** Returns the relative path of a file on this file store with a given URL. */
  fun getPath(url: URI): Path

  /**
   * Creates a new URL for a file of a particular category with a particular content type.
   *
   * This will typically delegate the construction of the relative path to
   * [PathGenerator.generatePath].
   */
  fun newUrl(timestamp: Instant, category: String, contentType: String): URI
}
