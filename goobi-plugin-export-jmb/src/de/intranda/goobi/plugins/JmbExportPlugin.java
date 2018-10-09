package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.beans.ProjectFileGroup;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.download.ExportMets;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.InvalidImagesException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.metadaten.MetadatenHelper;
import de.sub.goobi.metadaten.MetadatenImagesHelper;
import de.sub.goobi.metadaten.MetadatenVerifizierung;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.ExportFileformat;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.VirtualFileGroup;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
public class JmbExportPlugin extends ExportMets implements IExportPlugin, IPlugin {

	private static final String PLUGIN_NAME = "LzaExport";

	private static final Logger logger = Logger.getLogger(JmbExportPlugin.class);

	private String exportFolder = "/opt/digiverso/lza/";
	private static final Namespace metsNamespace = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
	private static final Namespace xlink = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");

	@Override
	public PluginType getType() {
		return PluginType.Export;
	}

	@Override
	public String getTitle() {
		return PLUGIN_NAME;
	}

	public String getDescription() {
		return PLUGIN_NAME;
	}

	@Override
	public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException,
			PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException,
			UghHelperException, ReadException, SwapException, DAOException, TypeNotAllowedForParentException {
		exportFolder = ConfigPlugins.getPluginConfig(this.getTitle()).getString("exportFolder", exportFolder);
//        exportFolder = ConfigPlugins.getPluginConfig(this).getString("exportFolder", exportFolder);

		return startExport(process, exportFolder);
	}

	@Override
	public boolean startExport(Process process, String destination)
			throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException, WriteException,
			MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException,
			DAOException, TypeNotAllowedForParentException {
		destination = ConfigPlugins.getPluginConfig(this.getTitle()).getString("exportFolder", destination);
//        destination = ConfigPlugins.getPluginConfig(this).getString("exportFolder", destination);
		this.myPrefs = process.getRegelsatz().getPreferences();
		// ConfigProjects cp = new ConfigProjects(process.getProjekt().getTitel());
		String atsPpnBand = process.getTitel();

		/*
		 * -------------------------------- Dokument einlesen
		 * --------------------------------
		 */
		Fileformat gdzfile;
		// Fileformat newfile;
		ExportFileformat newfile = MetadatenHelper
				.getExportFileformatByName(process.getProjekt().getFileFormatDmsExport(), process.getRegelsatz());
		try {
			gdzfile = process.readMetadataFile();

			newfile.setDigitalDocument(gdzfile.getDigitalDocument());
			gdzfile = newfile;

		} catch (Exception e) {
			Helper.setFehlerMeldung(Helper.getTranslation("exportError") + process.getTitel(), e);
			logger.error("Export abgebrochen, xml-LeseFehler", e);
			problems.add("Export cancelled: " + e.getMessage());
			return false;
		}

		trimAllMetadata(gdzfile.getDigitalDocument().getLogicalDocStruct());
		VariableReplacer replacer = new VariableReplacer(gdzfile.getDigitalDocument(), this.myPrefs, process, null);

		/*
		 * -------------------------------- Metadaten validieren
		 * --------------------------------
		 */

		if (ConfigurationHelper.getInstance().isUseMetadataValidation()) {
			MetadatenVerifizierung mv = new MetadatenVerifizierung();
			if (!mv.validate(gdzfile, this.myPrefs, process)) {
				problems.add("Export cancelled because of validation errors");
				problems.addAll(mv.getProblems());
				return false;
			}
		}

		/*
		 * -------------------------------- Speicherort vorbereiten und downloaden
		 * --------------------------------
		 */
		Path benutzerHome = Paths.get(destination);

		/* ggf. noch einen Vorgangsordner anlegen */
		if (process.getProjekt().isDmsImportCreateProcessFolder()) {
			benutzerHome = Paths.get(benutzerHome.toString(), process.getTitel());
			/* alte Import-Ordner löschen */
			StorageProvider.getInstance().deleteDir(benutzerHome);
//            if (!NIOFileUtils.deleteDir(benutzerHome)) {
//                Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), "Import folder could not be cleared");
//                problems.add("Export cancelled: Import folder could not be cleared.");
//                return false;
//            }
			/* alte Success-Ordner löschen */
			String successPath = process.getProjekt().getDmsImportSuccessPath();
			successPath = replacer.replace(successPath);
			Path successFile = Paths.get(successPath, process.getTitel());
			StorageProvider.getInstance().deleteDir(successFile);
//            if (!NIOFileUtils.deleteDir(successFile)) {
//                Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), "Success folder could not be cleared");
//                problems.add("Export cancelled: Success folder could not be cleared.");
//                return false;
//            }
			/* alte Error-Ordner löschen */
			String importPath = process.getProjekt().getDmsImportErrorPath();
			importPath = replacer.replace(importPath);
			Path errorfile = Paths.get(importPath, process.getTitel());
			StorageProvider.getInstance().deleteDir(errorfile);
//            if (!NIOFileUtils.deleteDir(errorfile)) {
//                Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), "Error folder could not be cleared");
//                problems.add("Export cancelled: Error folder could not be cleared.");
//                return false;
//            }

			if (!Files.exists(benutzerHome)) {
				Files.createDirectories(benutzerHome);
			}
		}

		try {

			imageDownload(process.getImagesTifDirectory(true), process, benutzerHome, atsPpnBand, "_tif");
			imageDownload(process.getImagesOrigDirectory(false), process, benutzerHome, "master_" + atsPpnBand, "");

			fulltextDownload(process, benutzerHome, atsPpnBand);

			String ed = process.getExportDirectory();
			ed = replacer.replace(ed);
			Path exportFolder = Paths.get(ed);
			if (Files.exists(exportFolder) && Files.isDirectory(exportFolder)) {
				List<Path> subdir = StorageProvider.getInstance().listFiles(ed);
//                List<Path> subdir = NIOFileUtils.listFiles(ed);

				for (Path dir : subdir) {
					if (Files.isDirectory(dir) && !StorageProvider.getInstance().listFiles(dir.toString()).isEmpty()) {
//                    if (Files.isDirectory(dir) && !NIOFileUtils.list(dir.toString()).isEmpty()) {
						if (!dir.getFileName().toString().matches(".+\\.\\d+")) {
							String suffix = dir.getFileName().toString()
									.substring(dir.getFileName().toString().lastIndexOf("_"));
							Path d = Paths.get(benutzerHome.toString(), atsPpnBand + suffix);
							if (!Files.exists(d)) {
								Files.createDirectories(d);
							}
							List<Path> files = StorageProvider.getInstance().listFiles(dir.toString());
//                            List<Path> files = NIOFileUtils.listFiles(dir.toString());
							for (Path file : files) {
								Path target = Paths.get(destination.toString(), file.getFileName().toString());
								Files.copy(file, target, NIOFileUtils.STANDARD_COPY_OPTIONS);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), e);
			problems.add("Export cancelled: " + e.getMessage());
			return false;
		}

		/*
		 * -------------------------------- zum Schluss Datei an gewünschten Ort
		 * exportieren entweder direkt in den Import-Ordner oder ins Benutzerhome
		 * anschliessend den Import-Thread starten --------------------------------
		 */

		String metsFilename = benutzerHome.toString() + "/" + atsPpnBand + ".xml";

		// write mets file
		writeMetsFile(process, metsFilename, gdzfile, false);

		// open written mets file
		SAXBuilder parser = new SAXBuilder();
		Document metsDoc = null;
		try {
			metsDoc = parser.build(metsFilename);
		} catch (JDOMException | IOException e) {
			Helper.setFehlerMeldung("error while parsing amd file");
			logger.error("error while parsing amd file", e);
			return false;
		}
		List<Element> fileGroupList = metsDoc.getRootElement().getChild("fileSec", metsNamespace).getChildren("fileGrp",
				metsNamespace);
//		String validationFolder = process.getProcessDataDirectory() + "validation"
//				+ FileSystems.getDefault().getSeparator() + "checksum";
		for (Element fileGrp : fileGroupList) {
			String fileGroupName = fileGrp.getAttributeValue("USE");
//			Map<String, String> hashes = null;
//			switch (fileGroupName) {
//			case "MASTER":
//				hashes = getChecksums(validationFolder, process.getImagesOrigDirectory(false));
//				break;
//			case "ALTO":
//				hashes = getChecksums(validationFolder, process.getOcrAltoDirectory());
////                    hashes = getChecksums(validationFolder, process.getAltoDirectory());
//				break;
//			case "DEFAULT":
////				hashes = getChecksums(validationFolder, process.getImagesTifDirectory(false));
//			case "FULLTEXT":
//				fileGroupList.remove(fileGrp);
//			default:
//				hashes = new HashMap<>();
//			}
//			if (!hashes.isEmpty()) {
//				List<Element> filesInGrp = fileGrp.getChildren("file", metsNamespace);
//				for (Element file : filesInGrp) {
//					Element flocat = file.getChild("FLocat", metsNamespace);
//					Path filename = Paths.get(flocat.getAttributeValue("href", xlink));
//					file.setAttribute("CHECKSUM", hashes.get(filename.getFileName().toString()));
//					file.setAttribute("CHECKSUMTYPE", "SHA-256");

//				}
//			}
			if ("MASTER".equals(fileGroupName) || "ALTO".equals(fileGroupName)) {
				List<Element> filesInGrp = fileGrp.getChildren("file", metsNamespace);
				for (Element file : filesInGrp) {
					Element flocat = file.getChild("FLocat", metsNamespace);
					Path filePath = Paths.get(flocat.getAttributeValue("href", xlink));
					String filename = filePath.getFileName().toString();
					Path pathToFile;
					if ("MASTER".equals(fileGroupName)) {
						pathToFile = Paths.get(process.getImagesOrigDirectory(false), filename);
					} else {
						pathToFile = Paths.get(process.getOcrAltoDirectory(), filename);
					}
					MessageDigest shamd = null;
					try {
						shamd = MessageDigest.getInstance("Sha-256");
					} catch (NoSuchAlgorithmException e) {
					}
					try (InputStream is = StorageProvider.getInstance().newInputStream(pathToFile);
							DigestInputStream dis = new DigestInputStream(is, shamd)) {
						int n = 0;
						byte[] buffer = new byte[8092];
						while (n != -1) {
							n = dis.read(buffer);
						}
					}
					String hash = getShaString(shamd);
					file.setAttribute("CHECKSUM", hash);
					file.setAttribute("CHECKSUMTYPE", "SHA-256");

				}

			}

		}
		XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());
		// FileOutputStream output = new
		// FileOutputStream("/home/robert/workspace/postexport/kleiuniv_PPN517154005.amd.xml");
		try {
			FileOutputStream output = new FileOutputStream(metsFilename);
			outputter.output(metsDoc, output);
		} catch (IOException e) {
			Helper.setFehlerMeldung("error while writing mets file");
			logger.error("error while writing mets file", e);
			return false;
		}
		return true;
	}

	private static String getShaString(MessageDigest messageDigest) {
		BigInteger bigInt = new BigInteger(1, messageDigest.digest());
		String sha256 = bigInt.toString(16).toLowerCase();
		while (sha256.length() < 64) {
			sha256 = "0" + sha256;
		}
		return sha256;
	}

	/**
	 * looks up and reads file in sub dir of validationFolder containing sha256
	 * checksums for the files of the process corresponding to folder
	 * 
	 * @param validationFolder root folder of system containing the hashfiles
	 * @param folder           this folders hashes are looked up
	 * @return hashmap of filenames and hashes of them
	 */
//	private Map<String, String> getChecksums(String validationFolder, String folder) {
//		Path path = Paths.get(folder);
//		// folder = folder.substring(folder.lastIndexOf(File.separator));
//		Map<String, String> answer = new HashMap<>();
//		if (path.getFileName().toString().endsWith("alto")) {
//			validationFolder = validationFolder + FileSystems.getDefault().getSeparator() + "ocr"
//					+ FileSystems.getDefault().getSeparator() + path.getFileName().toString() + ".sha256";
//		} else {
//			validationFolder = validationFolder + FileSystems.getDefault().getSeparator() + "images"
//					+ FileSystems.getDefault().getSeparator() + path.getFileName().toString() + ".sha256";
//		}
//		try (Stream<String> lines = Files.lines(Paths.get(validationFolder), Charset.defaultCharset())) {
//			for (String line : (Iterable<String>) lines::iterator) {
//				if (!line.startsWith("#")) {
//					String[] parts = line.split("  ");
//					if (parts.length > 1) {
//						answer.put(parts[1], parts[0]);
//					}
//				}
//			}
//		} catch (IOException e) {
//		}
//
//		return answer;
//	}

	/**
	 * run through all metadata and children of given docstruct to trim the strings
	 * calls itself recursively
	 */
	private void trimAllMetadata(DocStruct inStruct) {
		/* trimm all metadata values */
		if (inStruct.getAllMetadata() != null) {
			for (Metadata md : inStruct.getAllMetadata()) {
				if (md.getValue() != null) {
					md.setValue(md.getValue().trim());
				}
			}
		}

		/* run through all children of docstruct */
		if (inStruct.getAllChildren() != null) {
			for (DocStruct child : inStruct.getAllChildren()) {
				trimAllMetadata(child);
			}
		}
	}

	public void fulltextDownload(Process process, Path benutzerHome, String atsPpnBand)
			throws IOException, InterruptedException, SwapException, DAOException {

		// download sources
		Path sources = Paths.get(process.getSourceDirectory());
		if (Files.exists(sources) && !StorageProvider.getInstance().list(sources.toString()).isEmpty()) {
//        if (Files.exists(sources) && !NIOFileUtils.list(sources.toString()).isEmpty()) {
			Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + "_src");
			if (!Files.exists(destination)) {
				Files.createDirectories(destination);
			}
			List<Path> dateien = StorageProvider.getInstance().listFiles(process.getSourceDirectory());
//            List<Path> dateien = NIOFileUtils.listFiles(process.getSourceDirectory());
			for (Path dir : dateien) {
				Path meinZiel = Paths.get(destination.toString(), dir.getFileName().toString());
				Files.copy(dir, meinZiel, NIOFileUtils.STANDARD_COPY_OPTIONS);
			}
		}

		Path ocr = Paths.get(process.getOcrDirectory());
		if (Files.exists(ocr)) {
			List<Path> folder = StorageProvider.getInstance().listFiles(process.getOcrDirectory());
//            List<Path> folder = NIOFileUtils.listFiles(process.getOcrDirectory());
			for (Path dir : folder) {
				if (Files.isDirectory(dir) && !StorageProvider.getInstance().list(dir.toString()).isEmpty()) {
//                if (Files.isDirectory(dir) && !NIOFileUtils.list(dir.toString()).isEmpty()) {
					String suffix = dir.getFileName().toString()
							.substring(dir.getFileName().toString().lastIndexOf("_"));
					Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + suffix);
					if (!Files.exists(destination)) {
						Files.createDirectories(destination);
					}
					List<Path> files = StorageProvider.getInstance().listFiles(dir.toString());
//                    List<Path> files = NIOFileUtils.listFiles(dir.toString());
					for (Path file : files) {
						Path target = Paths.get(destination.toString(), file.getFileName().toString());
						Files.copy(file, target, NIOFileUtils.STANDARD_COPY_OPTIONS);
					}
				}
			}
		}
	}

	public void imageDownload(String sourceFolder, Process process, Path benutzerHome, String atsPpnBand,
			final String ordnerEndung) throws IOException, InterruptedException, SwapException, DAOException {

		/*
		 * -------------------------------- dann den Ausgangspfad ermitteln
		 * --------------------------------
		 */
		Path tifOrdner = Paths.get(sourceFolder);

		/*
		 * -------------------------------- jetzt die Ausgangsordner in die Zielordner
		 * kopieren --------------------------------
		 */
		Path zielTif = Paths.get(benutzerHome.toString(), atsPpnBand + ordnerEndung);
		if (Files.exists(tifOrdner) && !StorageProvider.getInstance().list(tifOrdner.toString()).isEmpty()) {
//        if (Files.exists(tifOrdner) && !NIOFileUtils.list(tifOrdner.toString()).isEmpty()) {

			/* bei Agora-Import einfach den Ordner anlegen */
			if (!Files.exists(zielTif)) {
				Files.createDirectories(zielTif);
			}

			/* jetzt den eigentlichen Kopiervorgang */
			List<Path> files = StorageProvider.getInstance().listFiles(tifOrdner.toString(), NIOFileUtils.DATA_FILTER);
//            List<Path> files = NIOFileUtils.listFiles(tifOrdner.toString(), NIOFileUtils.DATA_FILTER);
			for (Path file : files) {
				Path target = Paths.get(zielTif.toString(), file.getFileName().toString());
				Files.copy(file, target, NIOFileUtils.STANDARD_COPY_OPTIONS);
			}
		}

		if (ConfigurationHelper.getInstance().isExportFilesFromOptionalMetsFileGroups()) {

			List<ProjectFileGroup> myFilegroups = process.getProjekt().getFilegroups();
			if (myFilegroups != null && myFilegroups.size() > 0) {
				for (ProjectFileGroup pfg : myFilegroups) {
					// check if source files exists
					if (pfg.getFolder() != null && pfg.getFolder().length() > 0) {
						Path folder = Paths.get(process.getMethodFromName(pfg.getFolder()));
						if (folder != null && Files.exists(folder)
								&& !StorageProvider.getInstance().list(folder.toString()).isEmpty()) {
//                        if (folder != null && Files.exists(folder) && !NIOFileUtils.list(folder.toString()).isEmpty()) {
							List<Path> files = StorageProvider.getInstance().listFiles(folder.toString());
//                            List<Path> files = NIOFileUtils.listFiles(folder.toString());
							for (Path file : files) {
								Path target = Paths.get(zielTif.toString(), file.getFileName().toString());
								Files.copy(file, target, NIOFileUtils.STANDARD_COPY_OPTIONS);
							}
						}
					}
				}
			}
		}
	}

	@Override
	public void setExportFulltext(boolean exportFulltext) {
	}

	@Override
	public void setExportImages(boolean exportImages) {
	}

	/**
	 * only difference to inherrited method is that this one does not include the
	 * filegroups "DEFAULT" and "FULLTEXT"
	 */
	@Override
	protected boolean writeMetsFile(Process myProzess, String targetFileName, Fileformat gdzfile,
			boolean writeLocalFilegroup) throws PreferencesException, WriteException, IOException, InterruptedException,
			SwapException, DAOException, TypeNotAllowedForParentException {

		ExportFileformat mm = MetadatenHelper.getExportFileformatByName(myProzess.getProjekt().getFileFormatDmsExport(),
				myProzess.getRegelsatz());
		mm.setWriteLocal(writeLocalFilegroup);
		String imageFolderPath = myProzess.getImagesTifDirectory(true);
		Path imageFolder = Paths.get(imageFolderPath);
		/*
		 * before creating mets file, change relative path to absolute -
		 */
		DigitalDocument dd = gdzfile.getDigitalDocument();

		MetadatenImagesHelper mih = new MetadatenImagesHelper(this.myPrefs, dd);

		if (dd.getFileSet() == null || dd.getFileSet().getAllFiles().isEmpty()) {
			Helper.setMeldung(myProzess.getTitel()
					+ ": digital document does not contain images; temporarily adding them for mets file creation");
			mih.createPagination(myProzess, null);
		} else {
			mih.checkImageNames(myProzess, imageFolder.getFileName().toString());
		}

		/*
		 * get the topstruct element of the digital document depending on anchor
		 * property
		 */
		DocStruct topElement = dd.getLogicalDocStruct();
		if (topElement.getType().isAnchor()) {
			if (topElement.getAllChildren() == null || topElement.getAllChildren().size() == 0) {
				throw new PreferencesException(myProzess.getTitel()
						+ ": the topstruct element is marked as anchor, but does not have any children for physical docstrucs");
			} else {
				topElement = topElement.getAllChildren().get(0);
			}
		}

		/*
		 * -------------------------------- if the top element does not have any image
		 * related, set them all --------------------------------
		 */

		if (ConfigurationHelper.getInstance().isExportValidateImages()) {

			if (topElement.getAllToReferences("logical_physical") == null
					|| topElement.getAllToReferences("logical_physical").size() == 0) {
				if (dd.getPhysicalDocStruct() != null && dd.getPhysicalDocStruct().getAllChildren() != null) {
					Helper.setMeldung(myProzess.getTitel()
							+ ": topstruct element does not have any referenced images yet; temporarily adding them for mets file creation");
					for (DocStruct mySeitenDocStruct : dd.getPhysicalDocStruct().getAllChildren()) {
						topElement.addReferenceTo(mySeitenDocStruct, "logical_physical");
					}
				} else {
					Helper.setFehlerMeldung(
							myProzess.getTitel() + ": could not find any referenced images, export aborted");
					dd = null;
					return false;
				}
			}

			for (ContentFile cf : dd.getFileSet().getAllFiles()) {
				String location = cf.getLocation();
				// If the file's location string shoes no sign of any protocol,
				// use the file protocol.
				if (!location.contains("://")) {
					if (!location.matches("^[A-Z]:.*") && !location.matches("^\\/.*")) {
						// is a relative path
						Path f = Paths.get(imageFolder.toString(), location);
						location = f.toString();
					}
					location = "file://" + location;
				}
				cf.setLocation(location);
			}
		}
		mm.setDigitalDocument(dd);

		/*
		 * -------------------------------- wenn Filegroups definiert wurden, werden
		 * diese jetzt in die Metsstruktur übernommen --------------------------------
		 */
		// Replace all pathes with the given VariableReplacer, also the file
		// group pathes!
		VariableReplacer vp = new VariableReplacer(mm.getDigitalDocument(), this.myPrefs, myProzess, null);
		List<ProjectFileGroup> myFilegroups = myProzess.getProjekt().getFilegroups();

		if (myFilegroups != null && myFilegroups.size() > 0) {
			for (ProjectFileGroup pfg : myFilegroups) {
				// remove Default and Fulltext
				if (pfg.getName() == "DEFAULT" || pfg.getName() == "FULLTEXT") {
					myFilegroups.remove(pfg);
				} else {
					// check if source files exists
					if (pfg.getFolder() != null && pfg.getFolder().length() > 0) {
						String foldername = myProzess.getMethodFromName(pfg.getFolder());
						if (foldername != null) {
							Path folder = Paths.get(myProzess.getMethodFromName(pfg.getFolder()));
							if (folder != null && StorageProvider.getInstance().isFileExists(folder)
									&& !StorageProvider.getInstance().list(folder.toString()).isEmpty()) {
								VirtualFileGroup v = new VirtualFileGroup();
								v.setName(pfg.getName());
								v.setPathToFiles(vp.replace(pfg.getPath()));
								v.setMimetype(pfg.getMimetype());
								v.setFileSuffix(pfg.getSuffix());
								mm.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
							}
						}
					} else {

						VirtualFileGroup v = new VirtualFileGroup();
						v.setName(pfg.getName());
						v.setPathToFiles(vp.replace(pfg.getPath()));
						v.setMimetype(pfg.getMimetype());
						v.setFileSuffix(pfg.getSuffix());
						mm.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
					}
				}
			}
		}

		// Replace rights and digiprov entries.
		mm.setRightsOwner(vp.replace(myProzess.getProjekt().getMetsRightsOwner()));
		mm.setRightsOwnerLogo(vp.replace(myProzess.getProjekt().getMetsRightsOwnerLogo()));
		mm.setRightsOwnerSiteURL(vp.replace(myProzess.getProjekt().getMetsRightsOwnerSite()));
		mm.setRightsOwnerContact(vp.replace(myProzess.getProjekt().getMetsRightsOwnerMail()));
		mm.setDigiprovPresentation(vp.replace(myProzess.getProjekt().getMetsDigiprovPresentation()));
		mm.setDigiprovReference(vp.replace(myProzess.getProjekt().getMetsDigiprovReference()));
		mm.setDigiprovPresentationAnchor(vp.replace(myProzess.getProjekt().getMetsDigiprovPresentationAnchor()));
		mm.setDigiprovReferenceAnchor(vp.replace(myProzess.getProjekt().getMetsDigiprovReferenceAnchor()));

		mm.setMetsRightsLicense(vp.replace(myProzess.getProjekt().getMetsRightsLicense()));
		mm.setMetsRightsSponsor(vp.replace(myProzess.getProjekt().getMetsRightsSponsor()));
		mm.setMetsRightsSponsorLogo(vp.replace(myProzess.getProjekt().getMetsRightsSponsorLogo()));
		mm.setMetsRightsSponsorSiteURL(vp.replace(myProzess.getProjekt().getMetsRightsSponsorSiteURL()));

		mm.setPurlUrl(vp.replace(myProzess.getProjekt().getMetsPurl()));
		mm.setContentIDs(vp.replace(myProzess.getProjekt().getMetsContentIDs()));

		String pointer = myProzess.getProjekt().getMetsPointerPath();
		pointer = vp.replace(pointer);
		mm.setMptrUrl(pointer);

		String anchor = myProzess.getProjekt().getMetsPointerPathAnchor();
		pointer = vp.replace(anchor);
		mm.setMptrAnchorUrl(pointer);

		mm.setGoobiID(String.valueOf(myProzess.getId()));

		// if (!ConfigMain.getParameter("ImagePrefix", "\\d{8}").equals("\\d{8}")) {
		List<String> images = new ArrayList<String>();
		if (ConfigurationHelper.getInstance().isExportValidateImages()) {
			try {
				// TODO andere Dateigruppen nicht mit image Namen ersetzen
				images = new MetadatenImagesHelper(this.myPrefs, dd).getDataFiles(myProzess, imageFolderPath);

				int sizeOfPagination = dd.getPhysicalDocStruct().getAllChildren().size();
				if (images != null) {
					int sizeOfImages = images.size();
					if (sizeOfPagination == sizeOfImages) {
						dd.overrideContentFiles(images);
					} else {
						String[] param = { String.valueOf(sizeOfPagination), String.valueOf(sizeOfImages) };
						Helper.setFehlerMeldung(Helper.getTranslation("imagePaginationError", param));
						return false;
					}
				}
			} catch (IndexOutOfBoundsException e) {
				logger.error(e);
				return false;
			} catch (InvalidImagesException e) {
				logger.error(e);
				return false;
			}
		} else {
			// create pagination out of virtual file names
			dd.addAllContentFiles();

		}
		if (ConfigurationHelper.getInstance().isExportInTemporaryFile()) {
			Path tempFile = StorageProvider.getInstance().createTemporaryFile(myProzess.getTitel(), ".xml");
			String filename = tempFile.toString();
			mm.write(filename);
			StorageProvider.getInstance().copyFile(tempFile, Paths.get(targetFileName));

			Path anchorFile = Paths.get(filename.replace(".xml", "_anchor.xml"));
			if (StorageProvider.getInstance().isFileExists(anchorFile)) {
				StorageProvider.getInstance().copyFile(anchorFile,
						Paths.get(targetFileName.replace(".xml", "_anchor.xml")));
				StorageProvider.getInstance().deleteDir(anchorFile);
			}
			StorageProvider.getInstance().deleteDir(tempFile);
		} else {
			mm.write(targetFileName);
		}
		Helper.setMeldung(null, myProzess.getTitel() + ": ", "ExportFinished");
		return true;
	}
}
