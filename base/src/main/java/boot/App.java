package boot;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import org.projectodd.shimdandy.ClojureRuntimeShim;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;

public class App {
    private static final String aetherJar = "aether-2.0.0-SNAPSHOT-standalone.jar";
    
    private static File[] podJars;
    private static File   bootdir;
    
    public static final String version = "2.0.0-SNAPSHOT";
    public static final String release = "r1";

    public static ClojureRuntimeShim aether;
    public static ClojureRuntimeShim core;

    public static File               getBootDir() {return bootdir; }
    public static ClojureRuntimeShim getAether()  {return aether; }
    public static ClojureRuntimeShim getCore()    {return core; }
    
    public static ClojureRuntimeShim newShim(File[] jarFiles) throws Exception {
        URL[] urls = new URL[jarFiles.length];
        
        for (int i=0; i<jarFiles.length; i++) urls[i] = jarFiles[i].toURI().toURL();
        
        ClassLoader cl = new URLClassLoader(urls, App.class.getClassLoader());
        ClojureRuntimeShim rt = ClojureRuntimeShim.newRuntime(cl);

        rt.require("boot.pod");
        rt.invoke("boot.pod/seal-app-classloader");

        return rt; }
    
    public static ClojureRuntimeShim newPod() throws Exception {
        return newShim(podJars); }
    
    public static ClojureRuntimeShim newPod(File[] jarFiles) throws Exception {
        File[] files = new File[jarFiles.length + podJars.length];
        
        for (int i=0; i<podJars.length; i++) files[i] = podJars[i];
        for (int i=0; i<jarFiles.length; i++) files[i + podJars.length] = jarFiles[i];
        
        return newShim(files); }
    
    public static void extractResource(String resource, File outfile) throws Exception {
        ClassLoader  cl  = Thread.currentThread().getContextClassLoader();
        InputStream  in  = cl.getResourceAsStream(resource);
        OutputStream out = new FileOutputStream(outfile);
        int          n   = 0;
        byte[]       buf = new byte[4096];

        try { while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }
        finally {in.close(); out.close(); }}
    
    public static void ensureResourceFile(String r, File f) throws Exception {
        if (! f.exists()) extractResource(r, f); }
    
    public static File[] getDeps(ClojureRuntimeShim shim, String sym) {
        shim.require("boot.aether");
        return (File[]) shim.invoke("boot.aether/resolve-dependency-jars", sym, version); }
    
    public static void setOffline(String x) throws Exception {
        aether.invoke("boot.aether/set-offline!", x); }
    
    public static void setUpdate(String x) throws Exception {
        aether.invoke("boot.aether/set-update!", x); }
    
    public static void main(String[] args) throws Exception {
        File homedir    = new File(System.getProperty("user.home"));
        bootdir         = new File(homedir, ".boot");
        File reldir     = new File(bootdir, "lib");
        File jardir     = new File(reldir, release);
        File aetherFile = new File(jardir, aetherJar);

        jardir.mkdirs();
        
        ensureResourceFile(aetherJar, aetherFile);

        final ClojureRuntimeShim _aether = newShim(new File[] { aetherFile });
        
        podJars = getDeps(_aether, "boot/pod");

        final File[] d1 = getDeps(_aether, "boot/aether");
        final File[] d2 = getDeps(_aether, "boot/core");

        ExecutorService ex = Executors.newCachedThreadPool();

        Future f1 = ex.submit(new Callable() {
                public Object call() throws Exception {
                    return newShim(d1); }});

        Future f2 = ex.submit(new Callable() {
                public Object call() throws Exception {
                    return newShim(d2); }});

        aether  = (ClojureRuntimeShim) f1.get();
        core    = (ClojureRuntimeShim) f2.get();

        core.require("boot.main");
        core.invoke("boot.main/-main", args);
        
        ex.shutdown();
        
        System.exit(0); }}
