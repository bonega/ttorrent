/**
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.common;

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jargs.gnu.CmdLineParser;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A torrent file tracked by the controller's BitTorrent tracker.
 *
 * <p>
 * This class represents an active torrent on the tracker. The torrent
 * information is kept in-memory, and is created from the byte blob one would
 * usually find in a <tt>.torrent</tt> file.
 * </p>
 *
 * <p>
 * Each torrent also keeps a repository of the peers seeding and leeching this
 * torrent from the tracker.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Metainfo_File_Structure">Torrent meta-info file structure specification</a>
 */
public class Torrent {

	private static final Logger logger =
		LoggerFactory.getLogger(Torrent.class);

	/** Torrent file piece length (in bytes), we use 512 kB. */
	private static final int PIECE_LENGTH = 512 * 1024;

	public static final int PIECE_HASH_SIZE = 20;

	/** The query parameters encoding when parsing byte strings. */
	public static final String BYTE_ENCODING = "ISO-8859-1";

	/**
	 *
	 * @author dgiffin
	 * @author mpetazzoni
	 */
	public static class TorrentFile {

		public final File file;
		public final long size;

		public TorrentFile(File file, long size) {
			this.file = file;
			this.size = size;
		}
	};


	protected final byte[] encoded;
	protected final byte[] encoded_info;
	protected final Map<String, BEValue> decoded;
	protected final Map<String, BEValue> decoded_info;

	private final byte[] info_hash;
	private final String hex_info_hash;

	private final List<List<URI>> trackers;
	private final Set<URI> allTrackers;
	private final Date creationDate;
	private final String comment;
	private final String createdBy;
	private final String name;
	private final long size;
	protected final List<TorrentFile> files;

	private final boolean seeder;

	/**
	 * Create a new torrent from meta-info binary data.
	 *
	 * Parses the meta-info data (which should be B-encoded as described in the
	 * BitTorrent specification) and create a Torrent object from it.
	 *
	 * @param torrent The meta-info byte data.
	 * @param parent The parent directory or location of the torrent files.
	 * @param seeder Whether we'll be seeding for this torrent or not.
	 * @throws IOException When the info dictionary can't be read or
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 * @throws NoSuchAlgorithmException If the SHA-1 algorithm is not
	 * available.
	 */
	public Torrent(byte[] torrent, boolean seeder)
		throws IOException, NoSuchAlgorithmException {
		this.encoded = torrent;
		this.seeder = seeder;

		this.decoded = BDecoder.bdecode(
				new ByteArrayInputStream(this.encoded)).getMap();

		this.decoded_info = this.decoded.get("info").getMap();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BEncoder.bencode(this.decoded_info, baos);
		this.encoded_info = baos.toByteArray();
		this.info_hash = Torrent.hash(this.encoded_info);
		this.hex_info_hash = Torrent.byteArrayToHexString(this.info_hash);

		/**
		 * Parses the announce information from the decoded meta-info
		 * structure.
		 *
		 * <p>
		 * If the torrent doesn't define an announce-list, use the mandatory
		 * announce field value as the single tracker in a single announce
		 * tier.  Otherwise, the announce-list must be parsed and the trackers
		 * from each tier extracted.
		 * </p>
		 *
		 * @see <a href="http://bittorrent.org/beps/bep_0012.html">BitTorrent BEP#0012 "Multitracker Metadata Extension"</a>
		 */
		try {
			this.trackers = new ArrayList<List<URI>>();
			this.allTrackers = new HashSet<URI>();

			if (!this.decoded.containsKey("announce-list")) {
				URI tracker = new URI(this.decoded.get("announce").getString());
				this.allTrackers.add(tracker);

				// Build a single-tier announce list.
				List<URI> tier = new ArrayList<URI>();
				tier.add(tracker);
				this.trackers.add(tier);
			} else {
				List<BEValue> tiers = this.decoded.get("announce-list").getList();
				for (BEValue tv : tiers) {
					List<BEValue> trackers = tv.getList();
					if (trackers.isEmpty()) {
						continue;
					}

					List<URI> tier = new ArrayList<URI>();
					for (BEValue tracker : trackers) {
						URI uri = new URI(tracker.getString());

						// Make sure we're not adding duplicate trackers.
						if (!this.allTrackers.contains(uri)) {
							tier.add(uri);
							this.allTrackers.add(uri);
						}
					}

					// Only add the tier if it's not empty.
					if (!tier.isEmpty()) {
						this.trackers.add(tier);
					}
				}
			}
		} catch (URISyntaxException use) {
			throw new IOException(use);
		}

		this.creationDate = this.decoded.containsKey("creation date")
			? new Date(this.decoded.get("creation date").getLong() * 1000)
			: null;
		this.comment = this.decoded.containsKey("comment")
			? this.decoded.get("comment").getString()
			: null;
		this.createdBy = this.decoded.containsKey("created by")
			? this.decoded.get("created by").getString()
			: null;
		this.name = this.decoded_info.get("name").getString();

		this.files = new LinkedList<TorrentFile>();

		// Parse multi-file torrent file information structure.
		if (this.decoded_info.containsKey("files")) {
			for (BEValue file : this.decoded_info.get("files").getList()) {
				Map<String, BEValue> fileInfo = file.getMap();
				StringBuilder path = new StringBuilder();
				for (BEValue pathElement : fileInfo.get("path").getList()) {
					path.append(File.separator)
						.append(pathElement.getString());
				}
				this.files.add(new TorrentFile(
					new File(this.name, path.toString()),
					fileInfo.get("length").getLong()));
			}
		} else {
			// For single-file torrents, the name of the torrent is
			// directly the name of the file.
			this.files.add(new TorrentFile(
				new File(this.name),
				this.decoded_info.get("length").getLong()));
		}

		// Calculate the total size of this torrent from its files' sizes.
		long size = 0;
		for (TorrentFile file : this.files) {
			size += file.size;
		}
		this.size = size;

		logger.info("{}-file torrent information:",
			this.isMultifile() ? "Multi" : "Single");
		logger.info("  Torrent name: {}", this.name);
		logger.info("  Announced at:");
		for (int i=0; i < this.trackers.size(); i++) {
			List<URI> tier = this.trackers.get(i);
			for (int j=0; j < tier.size(); j++) {
				logger.info("    {}{}",
					(j == 0 ? String.format("%2d. ", i+1) : "    "),
					tier.get(j));
			}
		}

		if (this.creationDate != null) {
			logger.info("  Created on..: {}", this.creationDate);
		}
		if (this.comment != null) {
			logger.info("  Comment.....: {}", this.comment);
		}
		if (this.createdBy != null) {
			logger.info("  Created by..: {}", this.createdBy);
		}

		if (this.isMultifile()) {
			logger.info("  Found {} file(s) in multi-file torrent structure.",
				this.files.size());
			int i = 0;
			for (TorrentFile file : this.files) {
				logger.debug("    {}. {} ({} byte(s))",
					new Object[] {
						String.format("%2d", ++i),
						file.file.getPath(),
						String.format("%,d", file.size)
					});
			}
		}

		logger.info("  Pieces......: {} piece(s) ({} byte(s)/piece)",
			(this.size / this.decoded_info.get("piece length").getInt()) + 1,
			this.decoded_info.get("piece length").getInt());
		logger.info("  Total size..: {} byte(s)",
			String.format("%,d", this.size));
	}

	/**
	 * Get this torrent's name.
	 *
	 * <p>
	 * For a single-file torrent, this is usually the name of the file. For a
	 * multi-file torrent, this is usually the name of a top-level directory
	 * containing those files.
	 * </p>
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Get this torrent's comment string.
	 */
	public String getComment() {
		return this.comment;
	}

	/**
	 * Get this torrent's creator (user, software, whatever...).
	 */
	public String getCreatedBy() {
		return this.createdBy;
	}

	/**
	 * Get the total size of this torrent.
	 */
	public long getSize() {
		return this.size;
	}

	/**
	 * Get the file names from this torrent.
	 *
	 * @return The list of relative filenames of all the files described in
	 * this torrent.
	 */
	public List<String> getFilenames() {
		List<String> filenames = new LinkedList<String>();
		for (TorrentFile file : this.files) {
			filenames.add(file.file.getPath());
		}
		return filenames;
	}

	/**
	 * Tells whether this torrent is multi-file or not.
	 */
	public boolean isMultifile() {
		return this.files.size() > 1;
	}

	/**
	 * Return the hash of the B-encoded meta-info structure of this torrent.
	 */
	public byte[] getInfoHash() {
		return this.info_hash;
	}

	/**
	 * Get this torrent's info hash (as an hexadecimal-coded string).
	 */
	public String getHexInfoHash() {
		return this.hex_info_hash;
	}

	/**
	 * Return a human-readable representation of this torrent object.
	 *
	 * <p>
	 * The torrent's name is used.
	 * </p>
	 */
	public String toString() {
		return this.getName();
	}

	/**
	 * Return the B-encoded meta-info of this torrent.
	 */
	public byte[] getEncoded() {
		return this.encoded;
	}

	/**
	 * Return the trackers for this torrent.
	 */
	public List<List<URI>> getAnnounceList() {
		return this.trackers;
	}

	/**
	 * Returns the number of trackers for this torrent.
	 */
	public int getTrackerCount() {
		return this.allTrackers.size();
	}

	/**
	 * Tells whether we were an initial seeder for this torrent.
	 */
	public boolean isSeeder() {
		return this.seeder;
	}

	/**
	 * Save this torrent meta-info structure into a .torrent file.
	 *
	 * @param output The stream to write to.
	 * @throws IOException If an I/O error occurs while writing the file.
	 */
	public void save(OutputStream output) throws IOException {
		output.write(this.getEncoded());
	}

	public static byte[] hash(byte[] data) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		md.update(data);
		return md.digest();
	}

	/**
	 * Convert a byte string to a string containing an hexadecimal
	 * representation of the original data.
	 *
	 * @param bytes The byte array to convert.
	 */
	public static String byteArrayToHexString(byte[] bytes) {
		BigInteger bi = new BigInteger(1, bytes);
		return String.format("%0" + (bytes.length << 1) + "X", bi);
	}

	/**
	 * Return an hexadecimal representation of the bytes contained in the
	 * given string, following the default, expected byte encoding.
	 *
	 * @param input The input string.
	 */
	public static String toHexString(String input) {
		try {
			byte[] bytes = input.getBytes(Torrent.BYTE_ENCODING);
			return Torrent.byteArrayToHexString(bytes);
		} catch (UnsupportedEncodingException uee) {
			return null;
		}
	}

	/**
	 * Determine how many threads to use for the piece hashing.
	 *
	 * <p>
	 * If the environment variable TTORRENT_HASHING_THREADS is set to an
	 * integer value greater than 0, its value will be used. Otherwise, it
	 * defaults to the number of processors detected by the Java Runtime.
	 * </p>
	 *
	 * @return How many threads to use for concurrent piece hashing.
	 */
	protected static int getHashingThreadsCount() {
		String threads = System.getenv("TTORRENT_HASHING_THREADS");

		if (threads != null) {
			try {
				int count = Integer.parseInt(threads);
				if (count > 0) {
					return count;
				}
			} catch (NumberFormatException nfe) {
				// Pass
			}
		}

		return Runtime.getRuntime().availableProcessors();
	}

	/** Torrent loading ---------------------------------------------------- */

	/**
	 * Load a torrent from the given torrent file.
	 *
	 * <p>
	 * This method assumes we are not a seeder and that local data needs to be
	 * validated.
	 * </p>
	 *
	 * @param torrent The abstract {@link File} object representing the
	 * <tt>.torrent</tt> file to load.
	 * @throws IOException When the torrent file cannot be read.
	 * @throws NoSuchAlgorithmException
	 */
	public static Torrent load(File torrent)
		throws IOException, NoSuchAlgorithmException {
		return Torrent.load(torrent, false);
	}

	/**
	 * Load a torrent from the given torrent file.
	 *
	 * @param torrent The abstract {@link File} object representing the
	 * <tt>.torrent</tt> file to load.
	 * @param seeder Whether we are a seeder for this torrent or not (disables
	 * local data validation).
	 * @throws IOException When the torrent file cannot be read.
	 * @throws NoSuchAlgorithmException
	 */
	public static Torrent load(File torrent, boolean seeder)
		throws IOException, NoSuchAlgorithmException {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(torrent);
			byte[] data = new byte[(int)torrent.length()];
			fis.read(data);
			return new Torrent(data, seeder);
		} finally {
			if (fis != null) {
				fis.close();
			}
		}
	}

	/** Torrent creation --------------------------------------------------- */

	/**
	 * Create a {@link Torrent} object for a file.
	 *
	 * <p>
	 * Hash the given file to create the {@link Torrent} object representing
	 * the Torrent metainfo about this file, needed for announcing and/or
	 * sharing said file.
	 * </p>
	 *
	 * @param source The file to use in the torrent.
	 * @param announce The announce URI that will be used for this torrent.
	 * @param createdBy The creator's name, or any string identifying the
	 * torrent's creator.
	 */
	public static Torrent create(File source, URI announce, String createdBy)
		throws NoSuchAlgorithmException, InterruptedException, IOException {
		return Torrent.create(source, null, announce, createdBy);
	}

	/**
	 * Create a {@link Torrent} object for a set of files.
	 *
	 * <p>
	 * Hash the given files to create the multi-file {@link Torrent} object
	 * representing the Torrent meta-info about them, needed for announcing
	 * and/or sharing these files. Since we created the torrent, we're
	 * considering we'll be a full initial seeder for it.
	 * </p>
	 *
	 * @param parent The parent directory or location of the torrent files,
	 * also used as the torrent's name.
	 * @param files The files to add into this torrent.
	 * @param announce The announce URI that will be used for this torrent.
	 * @param createdBy The creator's name, or any string identifying the
	 * torrent's creator.
	 */
	public static Torrent create(File parent, List<File> files, URI announce,
		String createdBy) throws NoSuchAlgorithmException,
		   InterruptedException, IOException {
		if (files == null || files.isEmpty()) {
			logger.info("Creating single-file torrent for {}...",
				parent.getName());
		} else {
			logger.info("Creating {}-file torrent {}...",
				files.size(), parent.getName());
		}

		Map<String, BEValue> torrent = new HashMap<String, BEValue>();
		torrent.put("announce", new BEValue(announce.toString()));
		torrent.put("creation date", new BEValue(new Date().getTime() / 1000));
		torrent.put("created by", new BEValue(createdBy));

		Map<String, BEValue> info = new TreeMap<String, BEValue>();
		info.put("name", new BEValue(parent.getName()));
		info.put("piece length", new BEValue(Torrent.PIECE_LENGTH));

		if (files == null || files.isEmpty()) {
			info.put("length", new BEValue(parent.length()));
			info.put("pieces", new BEValue(Torrent.hashFile(parent),
				Torrent.BYTE_ENCODING));
		} else {
			List<BEValue> fileInfo = new LinkedList<BEValue>();
			for (File file : files) {
				Map<String, BEValue> fileMap = new HashMap<String, BEValue>();
				fileMap.put("length", new BEValue(file.length()));

				LinkedList<BEValue> filePath = new LinkedList<BEValue>();
				while (file != null) {
					if (file.equals(parent)) {
						break;
					}

					filePath.addFirst(new BEValue(file.getName()));
					file = file.getParentFile();
				}

				fileMap.put("path", new BEValue(filePath));
				fileInfo.add(new BEValue(fileMap));
			}
			info.put("files", new BEValue(fileInfo));
			info.put("pieces", new BEValue(Torrent.hashFiles(files),
				Torrent.BYTE_ENCODING));
		}
		torrent.put("info", new BEValue(info));

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BEncoder.bencode(new BEValue(torrent), baos);
		return new Torrent(baos.toByteArray(), true);
	}

	/**
	 * A {@link Callable} to hash a data chunk.
	 *
	 * @author mpetazzoni
	 */
	private static class CallableChunkHasher implements Callable<String> {

		private final MessageDigest md;
		private final ByteBuffer data;

		CallableChunkHasher(ByteBuffer buffer)
			throws NoSuchAlgorithmException {
			this.md = MessageDigest.getInstance("SHA-1");

			this.data = ByteBuffer.allocate(buffer.remaining());
			buffer.mark();
			this.data.put(buffer);
			this.data.clear();
			buffer.reset();
		}

		@Override
		public String call() throws UnsupportedEncodingException {
			this.md.reset();
			this.md.update(this.data.array());
			return new String(md.digest(), Torrent.BYTE_ENCODING);
		}
	}

	/**
	 * Return the concatenation of the SHA-1 hashes of a file's pieces.
	 *
	 * <p>
	 * Hashes the given file piece by piece using the default Torrent piece
	 * length (see {@link #PIECE_LENGTH}) and returns the concatenation of
	 * these hashes, as a string.
	 * </p>
	 *
	 * <p>
	 * This is used for creating Torrent meta-info structures from a file.
	 * </p>
	 *
	 * @param file The file to hash.
	 */
	private static String hashFile(File file)
		throws NoSuchAlgorithmException, InterruptedException, IOException {
		return Torrent.hashFiles(Arrays.asList(new File[] { file }));
	}

	private static String hashFiles(List<File> files)
		throws NoSuchAlgorithmException, InterruptedException, IOException {
		int threads = getHashingThreadsCount();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		ByteBuffer buffer = ByteBuffer.allocate(Torrent.PIECE_LENGTH);
		List<Future<String>> results = new LinkedList<Future<String>>();
		StringBuilder hashes = new StringBuilder();

		long length = 0L;
		int pieces = 0;

		long start = System.nanoTime();
		for (File file : files) {
			logger.info("Hashing data from {} with {} threads ({} pieces)...",
				new Object[] {
					file.getName(),
					threads,
					(int) (Math.ceil(
						(double)file.length() / Torrent.PIECE_LENGTH))
				});

			length += file.length();

			FileInputStream fis = new FileInputStream(file);
			FileChannel channel = fis.getChannel();
			int step = 10;

			try {
				while (channel.read(buffer) > 0) {
					if (buffer.remaining() == 0) {
						buffer.clear();
						results.add(executor.submit(new CallableChunkHasher(buffer)));
					}

					if (results.size() >= threads) {
						pieces += accumulateHashes(hashes, results);
					}

					if (channel.position() / (double)channel.size() * 100f > step) {
						logger.info("  ... {}% complete", step);
						step += 10;
					}
				}
			} finally {
				channel.close();
				fis.close();
			}
		}

		// Hash the last bit, if any
		if (buffer.position() > 0) {
			buffer.limit(buffer.position());
			buffer.position(0);
			results.add(executor.submit(new CallableChunkHasher(buffer)));
		}

		pieces += accumulateHashes(hashes, results);

		// Request orderly executor shutdown and wait for hashing tasks to
		// complete.
		executor.shutdown();
		while (!executor.isTerminated()) {
			Thread.sleep(10);
		}
		long elapsed = System.nanoTime() - start;

		int expectedPieces = (int) (Math.ceil(
				(double)length / Torrent.PIECE_LENGTH));
		logger.info("Hashed {} file(s) ({} bytes) in {} pieces ({} expected) in {}ms.",
			new Object[] {
				files.size(),
				length,
				pieces,
				expectedPieces,
				String.format("%.1f", elapsed/1e6),
			});

		return hashes.toString();
	}

	/**
	 * Accumulate the piece hashes into a given {@link StringBuilder}.
	 *
	 * @param hashes The {@link StringBuilder} to append hashes to.
	 * @param results The list of {@link Future}s that will yield the piece
	 *	hashes.
	 */
	private static int accumulateHashes(StringBuilder hashes,
			List<Future<String>> results) throws InterruptedException, IOException {
		try {
			int pieces = results.size();
			for (Future<String> chunk : results) {
				hashes.append(chunk.get());
			}
			results.clear();
			return pieces;
		} catch (ExecutionException ee) {
			throw new IOException("Error while hashing the torrent data!", ee);
		}
	}

	/**
	 * Display program usage on the given {@link PrintStream}.
	 */
	private static void usage(PrintStream s) {
		usage(s, null);
	}

	/**
	 * Display a message and program usage on the given {@link PrintStream}.
	 */
	private static void usage(PrintStream s, String msg) {
		if (msg != null) {
			s.println(msg);
			s.println();
		}

		s.println("usage: Torrent [options] [file|directory]");
		s.println();
		s.println("Available options:");
		s.println("  -h,--help             Show this help and exit.");
		s.println("  -t,--torrent FILE     Use FILE to read/write torrent file.");
		s.println();
		s.println("  -c,--create           Create a new torrent file using " +
			"the given announce URL and data.");
		s.println("  -a,--announce         Tracker URL (can be repeated).");
		s.println();
	}

	/**
	 * Torrent reader and creator.
	 *
	 * <p>
	 * You can use the {@code main()} function of this {@link Torrent} class to
	 * read or create torrent files. See usage for details.
	 * </p>
	 *
	 * TODO: support multiple announce URLs.
	 */
	public static void main(String[] args) {
		BasicConfigurator.configure(new ConsoleAppender(
			new PatternLayout("%-5p: %m%n")));

		CmdLineParser parser = new CmdLineParser();
		CmdLineParser.Option help = parser.addBooleanOption('h', "help");
		CmdLineParser.Option filename = parser.addStringOption('t', "torrent");
		CmdLineParser.Option create = parser.addBooleanOption('c', "create");
		CmdLineParser.Option announce = parser.addStringOption('a', "announce");

		try {
			parser.parse(args);
		} catch (CmdLineParser.OptionException oe) {
			System.err.println(oe.getMessage());
			usage(System.err);
			System.exit(1);
		}

		// Display help and exit if requested
		if (Boolean.TRUE.equals((Boolean)parser.getOptionValue(help))) {
			usage(System.out);
			System.exit(0);
		}

		String filenameValue = (String)parser.getOptionValue(filename);
		if (filenameValue == null) {
			usage(System.err, "Torrent file must be provided!");
			System.exit(1);
		}

		Boolean createFlag = (Boolean)parser.getOptionValue(create);
		String announceURL = (String)parser.getOptionValue(announce);

		String[] otherArgs = parser.getRemainingArgs();

		if (Boolean.TRUE.equals(createFlag) &&
				(otherArgs.length != 1 || announceURL == null)) {
			usage(System.err, "Announce URL and a file or directory must be " +
				"provided to create a torrent file!");
			System.exit(1);
		}

		OutputStream fos = null;
		try {
			if (Boolean.TRUE.equals(createFlag)) {
				if (filenameValue != null) {
					fos = new FileOutputStream(filenameValue);
				} else {
					fos = System.out;
				}

				URI announceURI = new URI(announceURL);
				File source = new File(otherArgs[0]);
				if (!source.exists() || !source.canRead()) {
					throw new IllegalArgumentException(
						"Cannot access source file or directory " +
						source.getName());
				}

				String creator = String.format("%s (ttorrent)",
					System.getProperty("user.name"));

				Torrent torrent = null;
				if (source.isDirectory()) {
					File[] files = source.listFiles();
					Arrays.sort(files);
					torrent = Torrent.create(source, Arrays.asList(files),
						announceURI, creator);
				} else {
					torrent = Torrent.create(source, announceURI, creator);
				}

				torrent.save(fos);
			} else {
				Torrent.load(new File(filenameValue), true);
			}
		} catch (Exception e) {
			logger.error("{}", e.getMessage(), e);
			System.exit(2);
		} finally {
			if (fos != null && fos != System.out) {
				try {
					fos.close();
				} catch (IOException ioe) {
				}
			}
		}
	}
}
