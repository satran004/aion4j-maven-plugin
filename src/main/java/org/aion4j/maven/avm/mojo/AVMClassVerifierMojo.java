package org.aion4j.maven.avm.mojo;

import org.aion4j.avm.helper.exception.LocalAVMException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

@Mojo(name = "class-verifier", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class AVMClassVerifierMojo extends AVMAbstractBaseMojo {

    private final static String AVM_USERLIB_JAR = "org-aion-avm-userlib.jar";

    @Override
    protected void preexecuteLocalAvm() throws MojoExecutionException {

    }

    @Override
    protected void executeLocalAvm(ClassLoader avmClassloader, Object classVerfierImpl) throws MojoExecutionException {

        try {
            Method setInputclassesMethod = classVerfierImpl.getClass().getMethod("setInputClasses", List.class);

            Method verifyMethod = classVerfierImpl.getClass().getMethod("verify", String.class, String.class);

            String outputDir = project.getBuild().getOutputDirectory();

            getLog().debug("Output folder : " + outputDir);

            Path outputPath = Paths.get(outputDir);
            List<Path> paths = Files.walk(outputPath).filter(f -> f.toFile().getName().endsWith(".class")).collect(Collectors.toList());


            List<String> inputClasses = new ArrayList<>();

            List<String> userLibClasses = getUserlibApiClasses();
            inputClasses.addAll(userLibClasses);

            //Update inputClasses. All classes in target/classes folder.
            for(Path path: paths) {
                String inputDotClassName = getClassNameWithDot(outputPath, path);

                if(getLog().isDebugEnabled())
                    getLog().debug("Dot classname" + inputDotClassName);

                //Ignore module-info class.
                if(inputDotClassName.contains("module-info"))
                    continue;

                inputClasses.add(inputDotClassName);
            }

            if(getLog().isDebugEnabled())
                getLog().info("Input classes for verification: " + inputClasses);

            //Invoke verifier's setInptClasses method
            setInputclassesMethod.invoke(classVerfierImpl, inputClasses);

            List<VerificationError> errors = new ArrayList<>();

            for(Path path: paths) {
                String fullPath = path.toAbsolutePath().toString();
//                String fileName = path.toFile().getName();
//                String className = fileName.substring(0, fileName.length()-6);

                String className = getClassNameWithDot(outputPath, path);

                if(className.startsWith("org.aion.avm") || className.equals("module-info") ) //No need to verify if classname starts with org.aion
                    continue;

                getLog().debug("Let's verify class : " + className);

                try {
                    verifyMethod.invoke(classVerfierImpl, className, fullPath);
                } catch (Exception e) {
                    errors.add(new VerificationError(e, fullPath));
                    //throw new MojoExecutionException("AVM erfication failed for class : " + fullPath, e);
                }
            }

            if(errors.size() > 0) {

                getLog().error("AVM verification failed with the following reasons:");

                for(VerificationError ve: errors) {
                    try {
                        getLog().error(String.format("Verification failed for %s, error: %s", ve.file, ve.exception.getCause().getMessage()));
                        getLog().debug(String.format("Verification failed for %s", ve.file), ve.exception);
                    } catch (Exception e) {
                        getLog().error(ve.exception); //Just for safer side, incase ve.exception.getCause() returns null
                    }
                }

                throw new MojoExecutionException("AVM Verification failed");
            }

        } catch (Exception e) {
            throw new MojoExecutionException("[Verification Error]", e);
        }

    }

    private String getClassNameWithDot(Path outputPath, Path path) {
        Path relativePath = outputPath.relativize(path);

        String relPathAsString = relativePath.toString();

        if(getLog().isDebugEnabled())
            getLog().debug("Class file relative path: " + relPathAsString);

        String inputDotClassName = relPathAsString.substring(0, relPathAsString.length() - 6);

        //exp: org.test.HelloAvm
        inputDotClassName = inputDotClassName.replace(File.separator, ".");

        return inputDotClassName;
    }

    private List<String> getUserlibApiClasses() {
        String libFolderPath = getAvmLibDir();
        File libFolder = new File(libFolderPath);

        File userLibJarFile = new File(libFolder, AVM_USERLIB_JAR);

        if(!userLibJarFile.exists()) {
            getLog().error(AVM_USERLIB_JAR + " not found in " + libFolder.getAbsolutePath());
            return Collections.EMPTY_LIST;
        }

        List<String> userLibClasses = new ArrayList<>();
        try (JarFile jar = new JarFile(userLibJarFile)) {

            Enumeration<JarEntry> entries = jar.entries();

            while(entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String classNameWithSlash = entry.getName();

                if(!classNameWithSlash.endsWith(".class"))
                    continue;
                else
                    classNameWithSlash = classNameWithSlash.substring(0, classNameWithSlash.length() - 6); //remove .class ext


                //exp: org.test.HelloAvm
                String classNameWithDot = classNameWithSlash.replace("/", ".");
                classNameWithDot = classNameWithDot.replace("\\", ".");

                userLibClasses.add(classNameWithDot);
            }


        } catch (IOException e) {
            getLog().error("Failed reading " + AVM_USERLIB_JAR, e);
            return Collections.EMPTY_LIST;
        }

        return userLibClasses;
    }

    @Override
    protected void postExecuteLocalAvm(Object localAvmInstance) throws MojoExecutionException {

    }

    @Override
    protected Object getLocalAvmImplInstance(ClassLoader avmClassloader)  {
        try{
            Class clazz = avmClassloader.loadClass("org.aion4j.avm.helper.local.AVMClassVerifier");
            Constructor localAvmVerifierConstructor = clazz.getConstructor(boolean.class);

            Object classVerifierInstance = localAvmVerifierConstructor.newInstance(isLocal());
            return classVerifierInstance;
        } catch (Exception e) {
            getLog().debug("Error creating LocalAvmNode instance", e);
            throw new LocalAVMException(e);
        }
    }

    class VerificationError {
        public Exception exception;
        public String file;

        public VerificationError(Exception ex, String file) {
            this.exception = ex;
            this.file = file;
        }
    }
}
