package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.xeoh.plugins.base.annotations.PluginImplementation;

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

import ugh.dl.DocStruct;
import ugh.dl.ExportFileformat;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.download.ExportMets;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.metadaten.MetadatenHelper;
import de.sub.goobi.metadaten.MetadatenVerifizierung;

@PluginImplementation
public class JmbExportPlugin extends ExportMets implements IExportPlugin, IPlugin {

    private static final String PLUGIN_NAME = "LzaExport";

    private static final Logger logger = Logger.getLogger(JmbExportPlugin.class);

    private String exportFolder = "/opt/digiverso/viewer/hotfolder/";
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
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
            WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        exportFolder = ConfigPlugins.getPluginConfig(this).getString("exportFolder", exportFolder);

        return startExport(process, exportFolder);
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
            PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
            SwapException, DAOException, TypeNotAllowedForParentException {

        this.myPrefs = process.getRegelsatz().getPreferences();
        //        ConfigProjects cp = new ConfigProjects(process.getProjekt().getTitel());
        String atsPpnBand = process.getTitel();

        /*
         * -------------------------------- Dokument einlesen --------------------------------
         */
        Fileformat gdzfile;
        //      Fileformat newfile;
        ExportFileformat newfile = MetadatenHelper.getExportFileformatByName(process.getProjekt().getFileFormatDmsExport(), process.getRegelsatz());
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
         * -------------------------------- Metadaten validieren --------------------------------
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
         * -------------------------------- Speicherort vorbereiten und downloaden --------------------------------
         */
        String zielVerzeichnis;
        Path benutzerHome;
        if (process.getProjekt().isUseDmsImport()) {
            zielVerzeichnis = process.getProjekt().getDmsImportImagesPath();
            zielVerzeichnis = replacer.replace(zielVerzeichnis);
            benutzerHome = Paths.get(zielVerzeichnis);

            /* ggf. noch einen Vorgangsordner anlegen */
            if (process.getProjekt().isDmsImportCreateProcessFolder()) {
                benutzerHome = Paths.get(benutzerHome.toString(), process.getTitel());
                zielVerzeichnis = benutzerHome.toString();
                /* alte Import-Ordner löschen */
                if (!NIOFileUtils.deleteDir(benutzerHome)) {
                    Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), "Import folder could not be cleared");
                    problems.add("Export cancelled: Import folder could not be cleared.");
                    return false;
                }
                /* alte Success-Ordner löschen */
                String successPath = process.getProjekt().getDmsImportSuccessPath();
                successPath = replacer.replace(successPath);
                Path successFile = Paths.get(successPath, process.getTitel());
                if (!NIOFileUtils.deleteDir(successFile)) {
                    Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), "Success folder could not be cleared");
                    problems.add("Export cancelled: Success folder could not be cleared.");
                    return false;
                }
                /* alte Error-Ordner löschen */
                String importPath = process.getProjekt().getDmsImportErrorPath();
                importPath = replacer.replace(importPath);
                Path errorfile = Paths.get(importPath, process.getTitel());
                if (!NIOFileUtils.deleteDir(errorfile)) {
                    Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), "Error folder could not be cleared");
                    problems.add("Export cancelled: Error folder could not be cleared.");
                    return false;
                }

                if (!Files.exists(benutzerHome)) {
                    Files.createDirectories(benutzerHome);
                }
            }

        } else {
            zielVerzeichnis = destination + atsPpnBand;
            zielVerzeichnis = replacer.replace(zielVerzeichnis);
            // wenn das Home existiert, erst löschen und dann neu anlegen
            benutzerHome = Paths.get(zielVerzeichnis);
            if (!NIOFileUtils.deleteDir(benutzerHome)) {
                Helper.setFehlerMeldung("Export canceled: " + process.getTitel(), "Could not delete home directory");
                problems.add("Export cancelled: Could not delete home directory.");
                return false;
            }
            prepareUserDirectory(zielVerzeichnis);
        }
        /*
         * -------------------------------- der eigentliche Download der Images --------------------------------
         */
        try {

            imageDownload(process.getImagesTifDirectory(true), process, benutzerHome, atsPpnBand, "_tif");
            imageDownload(process.getImagesOrigDirectory(false), process, benutzerHome, "master_" + atsPpnBand, "");

            fulltextDownload(process, benutzerHome, atsPpnBand, "_tif");

            String ed = process.getExportDirectory();
            ed = replacer.replace(ed);
            Path exportFolder = Paths.get(ed);
            if (Files.exists(exportFolder) && Files.isDirectory(exportFolder)) {
                List<Path> subdir = NIOFileUtils.listFiles(ed);

                for (Path dir : subdir) {
                    if (Files.isDirectory(dir) && !NIOFileUtils.list(dir.toString()).isEmpty()) {
                        if (!dir.getFileName().toString().matches(".+\\.\\d+")) {
                            String suffix = dir.getFileName().toString().substring(dir.getFileName().toString().lastIndexOf("_"));
                            Path d = Paths.get(benutzerHome.toString(), atsPpnBand + suffix);
                            if (!Files.exists(d)) {
                                Files.createDirectories(d);
                            }
                            List<Path> files = NIOFileUtils.listFiles(dir.toString());
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
         * exportieren entweder direkt in den Import-Ordner oder ins
         * Benutzerhome anschliessend den Import-Thread starten
         * --------------------------------
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
        List<Element> fileGroupList = metsDoc.getRootElement().getChild("fileSec", metsNamespace).getChildren("fileGrp", metsNamespace);
        String validationFolder = process.getProcessDataDirectory() + "validation" + FileSystems.getDefault().getSeparator() + "checksum";
        for (Element fileGrp : fileGroupList) {
            String fileGroupName = fileGrp.getAttributeValue("USE");
            Map<String, String> hashes = null;
            switch (fileGroupName) {
                case "DEFAULT":
                    hashes = getChecksums(validationFolder, process.getImagesTifDirectory(false));
                    break;
                case "MASTER":
                    hashes = getChecksums(validationFolder, process.getImagesOrigDirectory(false));
                    break;
                case "FULLTEXT":
                    hashes = getChecksums(validationFolder, process.getAltoDirectory());
                    break;
            }
            if (!hashes.isEmpty()) {
                List<Element> filesInGrp = fileGrp.getChildren("file", metsNamespace);
                for (Element file : filesInGrp) {
                    Element flocat = file.getChild("FLocat", metsNamespace);
                    Path filename = Paths.get(flocat.getAttributeValue("href", xlink));
                    file.setAttribute("CHECKSUM", hashes.get(filename.getFileName().toString()));
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

    private Map<String, String> getChecksums(String validationFolder, String folder) {
        Path path = Paths.get(folder);
        //        folder = folder.substring(folder.lastIndexOf(File.separator));
        Map<String, String> answer = new HashMap<>();
        if (path.getFileName().toString().endsWith("alto")) {
            validationFolder = validationFolder + FileSystems.getDefault().getSeparator() + "ocr" + FileSystems.getDefault().getSeparator() + path
                    .getFileName().toString() + ".sha256";
        } else {
            validationFolder = validationFolder + FileSystems.getDefault().getSeparator() + "images" + FileSystems.getDefault().getSeparator() + path
                    .getFileName().toString() + ".sha256";
        }
        try (Stream<String> lines = Files.lines(Paths.get(validationFolder), Charset.defaultCharset())) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("#")) {
                    String[] parts = line.split("  ");
                    if (parts.length > 1) {
                        answer.put(parts[1], parts[0]);
                    }
                }
            }
        } catch (IOException e) {
        }

        return answer;
    }

    /**
     * run through all metadata and children of given docstruct to trim the strings calls itself recursively
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

    public void fulltextDownload(Process process, Path benutzerHome, String atsPpnBand, final String ordnerEndung) throws IOException,
            InterruptedException, SwapException, DAOException {

        // download sources
        Path sources = Paths.get(process.getSourceDirectory());
        if (Files.exists(sources) && !NIOFileUtils.list(sources.toString()).isEmpty()) {
            Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + "_src");
            if (!Files.exists(destination)) {
                Files.createDirectories(destination);
            }
            List<Path> dateien = NIOFileUtils.listFiles(process.getSourceDirectory());
            for (Path dir : dateien) {
                Path meinZiel = Paths.get(destination.toString(), dir.getFileName().toString());
                Files.copy(dir, meinZiel, NIOFileUtils.STANDARD_COPY_OPTIONS);
            }
        }

        Path ocr = Paths.get(process.getOcrDirectory());
        if (Files.exists(ocr)) {
            List<Path> folder = NIOFileUtils.listFiles(process.getOcrDirectory());
            for (Path dir : folder) {
                if (Files.isDirectory(dir) && !NIOFileUtils.list(dir.toString()).isEmpty()) {
                    String suffix = dir.getFileName().toString().substring(dir.getFileName().toString().lastIndexOf("_"));
                    Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + suffix);
                    if (!Files.exists(destination)) {
                        Files.createDirectories(destination);
                    }
                    List<Path> files = NIOFileUtils.listFiles(dir.toString());
                    for (Path file : files) {
                        Path target = Paths.get(destination.toString(), file.getFileName().toString());
                        Files.copy(file, target, NIOFileUtils.STANDARD_COPY_OPTIONS);
                    }
                }
            }
        }
    }

    public void imageDownload(String sourceFolder, Process process, Path benutzerHome, String atsPpnBand, final String ordnerEndung)
            throws IOException, InterruptedException, SwapException, DAOException {

        /*
         * -------------------------------- dann den Ausgangspfad ermitteln --------------------------------
         */
        Path tifOrdner = Paths.get(sourceFolder);

        /*
         * -------------------------------- jetzt die Ausgangsordner in die Zielordner kopieren --------------------------------
         */
        Path zielTif = Paths.get(benutzerHome.toString(), atsPpnBand + ordnerEndung);
        if (Files.exists(tifOrdner) && !NIOFileUtils.list(tifOrdner.toString()).isEmpty()) {

            /* bei Agora-Import einfach den Ordner anlegen */
            if (!Files.exists(zielTif)) {
                Files.createDirectories(zielTif);
            }

            /* jetzt den eigentlichen Kopiervorgang */
            List<Path> files = NIOFileUtils.listFiles(process.getImagesTifDirectory(true), NIOFileUtils.DATA_FILTER);
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
                        if (folder != null && Files.exists(folder) && !NIOFileUtils.list(folder.toString()).isEmpty()) {
                            List<Path> files = NIOFileUtils.listFiles(folder.toString());
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
}
