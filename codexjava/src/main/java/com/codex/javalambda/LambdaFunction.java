package com.codex.javalambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class LambdaFunction implements RequestHandler<Map<String, String>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, String> event, Context context) {
        String code = event.get("code");
        String input = event.get("input");
        String expectedOutput = event.get("expected_output");

        String resultOutput;
        String compileMessage;

        try {
            resultOutput = compileAndRunJavaCode(code, input);
            if (resultOutput.trim().equals(expectedOutput.trim())) {
                compileMessage = "Success";
            } else {
                compileMessage = "Failed";
            }
        } catch (Exception e) {
            resultOutput = e.getMessage();
            compileMessage = "Error";
        }

        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", 200);
        Map<String, Object> data = new HashMap<>();
        data.put("output", resultOutput);
        data.put("compileMessage", compileMessage);
        response.put("data", data);
        return response;
    }

    private String compileAndRunJavaCode(String code, String input) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        JavaFileObject javaFile = new JavaSourceFromString("Main", code);
        MemoryJavaFileManager fileManager = new MemoryJavaFileManager(compiler.getStandardFileManager(diagnostics, null, null));
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, null, null, Collections.singletonList(javaFile));

        if (!task.call()) {
            throw new CompilationException(diagnostics);
        }

        InMemoryClassLoader classLoader = new InMemoryClassLoader(fileManager.getClassBytes());
        Class<?> clazz = classLoader.loadClass("Main");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(outputStream);
             InputStream inputStream = new ByteArrayInputStream(input.getBytes())) {
            PrintStream oldOut = System.out;
            InputStream oldIn = System.in;

            System.setOut(printStream);
            System.setIn(inputStream);

            try {
                clazz.getMethod("main", String[].class).invoke(null, (Object) new String[]{});
            } finally {
                System.setOut(oldOut);
                System.setIn(oldIn);
            }
        }

        return outputStream.toString();
    }

    private static class JavaSourceFromString extends SimpleJavaFileObject {
        private final String code;

        protected JavaSourceFromString(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static class MemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, ByteArrayOutputStream> classBytes = new HashMap<>();

        protected MemoryJavaFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            classBytes.put(className, byteArrayOutputStream);
            return new SimpleJavaFileObject(URI.create("mem:///" + className + kind.extension), kind) {
                @Override
                public OutputStream openOutputStream() {
                    return byteArrayOutputStream;
                }
            };
        }

        public Map<String, byte[]> getClassBytes() {
            Map<String, byte[]> result = new HashMap<>();
            for (Map.Entry<String, ByteArrayOutputStream> entry : classBytes.entrySet()) {
                result.put(entry.getKey(), entry.getValue().toByteArray());
            }
            return result;
        }
    }

    private static class InMemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> classBytes;

        public InMemoryClassLoader(Map<String, byte[]> classBytes) {
            this.classBytes = classBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classBytes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static class CompilationException extends Exception {
        public CompilationException(DiagnosticCollector<JavaFileObject> diagnostics) {
            super(diagnostics.getDiagnostics().stream()
                    .map(diagnostic -> diagnostic.getMessage(null))
                    .reduce((msg1, msg2) -> msg1 + "\n" + msg2)
                    .orElse("Unknown compilation error"));
        }
    }
}
