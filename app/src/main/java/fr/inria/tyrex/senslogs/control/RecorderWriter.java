package fr.inria.tyrex.senslogs.control;

import android.content.Context;
import android.content.res.Resources;
import android.util.Pair;

import org.ini4j.Wini;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.inria.tyrex.senslogs.Application;
import fr.inria.tyrex.senslogs.model.RecordProperties;

/**
 * Creation of log files asynchronously in a folder
 */
public class RecorderWriter {

    private final static String descriptionFileName = "description.txt";

    /**
     * Object writable by {@link RecorderWriter}
     */
    public interface WritableObject {
        String getStorageFileName(Context context);
        String getWebPage(Resources resources);
        String getFieldsDescription(Resources resources);
        String[] getFields(Resources resources);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Context mContext;

    private StringBuilder buffer1 = new StringBuilder();
    private StringBuilder buffer2 = new StringBuilder();

    private Map<WritableObject, FileOutputStream> mSensorsFos;
    private Map<WritableObject, File> mSensorsFiles;
    private long mFilesSize;

    private File mOutputDirectory;

    public RecorderWriter(Context context) {
        mContext = context;
        mSensorsFos = new HashMap<>();
        mSensorsFiles = new HashMap<>();
    }

    public void init(Set<WritableObject> writableObjects, File outputDirectory)
            throws FileNotFoundException {

        mOutputDirectory = outputDirectory;

        List<String> fileNames = new ArrayList<>();

        for (WritableObject writableObject : writableObjects) {
            String fileName = avoidDuplicateFiles(fileNames,
                    writableObject.getStorageFileName(mContext)) + ".txt";
            File file = new File(outputDirectory, fileName);

            mSensorsFiles.put(writableObject, file);
            FileOutputStream fos = new FileOutputStream(file);
            mSensorsFos.put(writableObject, fos);
            mFilesSize = 0;


            /*
             * Write files headers
             */
            boolean first = true;
            for(String field : writableObject.getFields(mContext.getResources())) {

                if(!first) {
                    buffer2.append(' ');
                }
                buffer2.append(field);
                first = false;
            }
            buffer2.append('\n');
            byte[] bytes = buffer2.toString().getBytes();
            mFilesSize += bytes.length;

            try {
                fos.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
            buffer2.setLength(0);
        }
    }


    public void write(final WritableObject writableObject, final double diffTimeSystem,
                      final double diffTimeSensor, final Object[] values) {

        executor.execute(new Runnable() {
            @Override
            public void run() {

                FileOutputStream fos = mSensorsFos.get(writableObject);
                try {
                    buffer1.append(String.format("%.3f %.3f", diffTimeSystem, diffTimeSensor));

                    for (Object value : values) {
                        buffer1.append(' ');
                        buffer1.append(value.toString());
                    }
                    buffer1.append('\n');

                    byte[] bytes = buffer1.toString().getBytes();
                    mFilesSize += bytes.length;

                    fos.write(bytes);
                    buffer1.setLength(0);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

    }

    public void write(final WritableObject writableObject, final double diffTimeSystem) {

        executor.execute(new Runnable() {
            @Override
            public void run() {

                FileOutputStream fos = mSensorsFos.get(writableObject);
                try {
                    buffer2.append(diffTimeSystem);
                    buffer2.append('\n');

                    byte[] bytes = buffer2.toString().getBytes();
                    mFilesSize += bytes.length;

                    fos.write(bytes);
                    buffer2.setLength(0);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

    }


    public void finish() throws IOException {

        executor.shutdown();

        for (FileOutputStream fos : mSensorsFos.values()) {
            fos.flush();
            fos.close();
        }
    }

    private File writeDescriptionFile(RecordProperties properties) throws IOException {

//        Resources res = mContext.getResources();

        File file = new File(mOutputDirectory, descriptionFileName);

        Wini iniFile = properties.generateIniFile(file);

        if(iniFile == null) {
            return file;
        }

        String time = SimpleDateFormat.getDateTimeInstance().format(Calendar.getInstance().getTime());
        iniFile.setComment(time);

        iniFile.store();

//        FileOutputStream fos = new FileOutputStream(file);
//
//        String time = SimpleDateFormat.getDateTimeInstance().format(Calendar.getInstance().getTime());
//
//        byte[] bytes = res.getString(R.string.description_file_begin,
//                time, Build.MODEL, mSensorsFiles.size()).getBytes();
//        fos.write(bytes);
//
//        mFilesSize += bytes.length;
//
//        for (WritableObject sensor : mSensorsFiles.keySet()) {
//
//            bytes = String.format(res.getString(R.string.description_file_line),
//                    mSensorsFiles.get(sensor).getName(),
//                    sensor.getWebPage(res),
//                    sensor.getFieldsDescription(res))
//                    .getBytes();
//            fos.write(bytes);
//
//            mFilesSize += bytes.length;
//
//        }

        return file;
    }


    public long getFilesSize() {
        return mFilesSize;
    }

    private String avoidDuplicateFiles(List<String> fileNames, final String fileName) {

        String newFileName = fileName;

        // For sensors from sensor manager, default sensor storage name is will be name.txt and
        // for others name#1.txt, name#2.txt ...
        if (fileNames.contains(fileName) || fileName.charAt(fileName.length() - 1) == '#') {
            int i = 1;
            do {
                newFileName = fileName + i++;
            }
            while (fileNames.contains(newFileName)) ;
        }

        fileNames.add(newFileName);
        return newFileName;
    }


    public void removeFiles() {

        if (mOutputDirectory != null && mOutputDirectory.isDirectory()) {
            for (String child : mOutputDirectory.list()) {
                if(!(new File(mOutputDirectory, child).delete())) {
                    android.util.Log.e(Application.LOG_TAG, "Cannot delete writer tmp file");
                }
            }
            if(!mOutputDirectory.delete()) {
                android.util.Log.e(Application.LOG_TAG, "Cannot delete writer tmp folder");
            }
        }
        mOutputDirectory = null;
    }


    public Pair<File, ZipCreationTask> createZipFile(String fileName, RecordProperties properties)
            throws IOException {

        File outputFile = new File(mContext.getFilesDir(), fileName + ".zip");

        if (outputFile.exists()) {
            int i = 2;
            while ((outputFile = new File(mContext.getFilesDir(), fileName + "-" + i++ + ".zip")).exists());
        }

        Collection<File> inputFiles = new ArrayList<>(mSensorsFiles.values());
        inputFiles.add(writeDescriptionFile(properties));

        ZipCreationTask zipTask = new ZipCreationTask();
        ZipCreationTask.Params params = new ZipCreationTask.Params(outputFile, inputFiles);
        zipTask.execute(params);

        return new Pair<>(outputFile, zipTask);
    }




}
