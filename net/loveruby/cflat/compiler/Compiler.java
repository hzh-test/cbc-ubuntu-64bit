package net.loveruby.cflat.compiler;
import net.loveruby.cflat.parser.*;
import net.loveruby.cflat.ast.*;
import net.loveruby.cflat.entity.DefinedFunction;
import net.loveruby.cflat.ir.IR;
import net.loveruby.cflat.type.*;
import net.loveruby.cflat.utils.*;
import net.loveruby.cflat.exception.*;
import java.util.*;
import java.io.*;

public class Compiler {
    // #@@range/main{
    static final public String ProgramName = "cbc";
    static final public String Version = "1.0.0";

    static public void main(String[] args) {
        new Compiler(ProgramName).commandMain(args);
    }

    protected ErrorHandler errorHandler;

    public Compiler(String programName) {
        this.errorHandler = new ErrorHandler(programName);
    }
    // #@@}

    public void commandMain(String[] origArgs) {
        Options opts = new Options();
        List<SourceFile> srcs = null;
        try {
            srcs = opts.parse(Arrays.asList(origArgs));
        }
        catch (OptionParseError err) {
            errorHandler.error(err.getMessage());
            errorHandler.error("Try cbc --help for option usage");
            System.exit(1);
        }
        if (opts.mode() == CompilerMode.CheckSyntax) {
            boolean failed = false;
            for (SourceFile src : srcs) {
                if (isValidSyntax(src, opts)) {
                    System.out.println(src.name() + ": Syntax OK");
                }
                else {
                    System.out.println(src.name() + ": Syntax Error");
                    failed = true;
                }
            }
            System.exit(failed ? 1 : 0);
        }
        else {
            try {
                buildTarget(srcs, opts);
                System.exit(0);
            }
            catch (CompileException ex) {
                errorHandler.error(ex.getMessage());
                System.exit(1);
            }
        }
    }

    private void errorExit(String msg) {
        errorHandler.error(msg);
        System.exit(1);
    }

    protected boolean isValidSyntax(SourceFile src, Options opts) {
        try {
            parseFile(src, opts);
            return true;
        }
        catch (SyntaxException ex) {
            return false;
        }
        catch (FileException ex) {
            errorHandler.error(ex.getMessage());
            return false;
        }
    }

    // #@@range/buildTarget{
    protected void buildTarget(List<SourceFile> srcs, Options opts)
                                        throws CompileException {
        for (SourceFile src : srcs) {
            compileFile(src, opts);
        }
        if (! opts.isLinkRequired()) System.exit(0);
        if (! opts.isGeneratingSharedLibrary()) {
            generateExecutable(opts);
        }
        else {
            generateSharedLibrary(opts);
        }
    }
    // #@@}

    protected void compileFile(SourceFile src, Options opts)
                                        throws CompileException {
        if (src.isCflatSource()) {
            AST ast = parseFile(src, opts);
            switch (opts.mode()) {
            case DumpTokens:
                dumpTokens(ast.sourceTokens(), System.out);
                return;
            case DumpAST:
                ast.dump();
                return;
            case DumpStmt:
                findStmt(ast).dump();
                return;
            case DumpExpr:
                findExpr(ast).dump();
                return;
            }
            ast.setTypeTable(opts.typeTable());
            semanticAnalysis(ast, opts);
            switch (opts.mode()) {
            case DumpReference:
                return;
            case DumpSemantic:
                ast.dump();
                return;
            }
            IR ir = new IRGenerator(errorHandler).generate(ast);
            if (opts.mode() == CompilerMode.DumpIR) {
                ir.dump();
                return;
            }
            String asm = generateAssembly(ir, opts);
            if (opts.mode() == CompilerMode.DumpAsm) {
                System.out.println(asm);
                return;
            }
            writeFile(src.asmFileName(opts), asm);
            src.setCurrentName(src.asmFileName(opts));
            if (opts.mode() == CompilerMode.Compile) {
                return;
            }
        }
        if (! opts.isAssembleRequired()) return;
        if (src.isAssemblySource()) {
            assemble(src.asmFileName(opts), src.objFileName(opts), opts);
            src.setCurrentName(src.objFileName(opts));
        }
    }

    protected void dumpTokens(CflatToken tokens, PrintStream s) {
        for (CflatToken t : tokens) {
            printPair(t.kindName(), t.dumpedImage(), s);
        }
    }

    static final protected int numLeftColumns = 24;

    protected void printPair(String key, String value, PrintStream s) {
        s.print(key);
        for (int n = numLeftColumns - key.length(); n > 0; n--) {
            s.print(" ");
        }
        s.println(value);
    }

    protected StmtNode findStmt(AST ast) {
        for (DefinedFunction f : ast.definedFunctions()) {
            if (f.name().equals("main")) {
                StmtNode stmt = f.body().stmts().get(0);
                if (stmt == null) {
                    errorExit("main() has no stmt");
                }
                return stmt;
            }
        }
        errorExit("source file does not contains main()");
        return null;   // never reach
    }

    protected ExprNode findExpr(AST ast) {
        StmtNode stmt = findStmt(ast);
        if (stmt instanceof ExprStmtNode) {
            return ((ExprStmtNode)stmt).expr();
        }
        else if (stmt instanceof ReturnNode) {
            return ((ReturnNode)stmt).expr();
        }
        else {
            errorExit("source file does not contains single expression");
            return null;   // never reach
        }
    }

    protected AST parseFile(SourceFile src, Options opts)
                            throws SyntaxException, FileException {
        return Parser.parseFile(new File(src.currentName()),
                                opts.loader(),
                                errorHandler,
                                opts.doesDebugParser());
    }

    protected void semanticAnalysis(AST ast, Options opts)
                                        throws SemanticException {
        new LocalResolver(errorHandler).resolve(ast);
        new TypeResolver(errorHandler).resolve(ast);
        ast.typeTable().semanticCheck(errorHandler);
        new DereferenceChecker(errorHandler).check(ast);
        if (opts.mode() == CompilerMode.DumpReference) {
            ast.dump();
            return;
        }
        new TypeChecker(errorHandler).check(ast);
    }

    protected String generateAssembly(IR ir, Options opts) {
        CodeGenerator gen = opts.codeGenerator(errorHandler);
        return gen.generate(ir);
    }

    protected void assemble(String srcPath,
                            String destPath,
                            Options opts) throws IPCException {
        List<Object> cmd = new ArrayList<Object>();
        cmd.add("as");
        cmd.addAll(opts.asOptions());
        cmd.add("-o");
        cmd.add(destPath);
        cmd.add(srcPath);
        invoke(cmd, opts.isVerboseMode());
    }

    static final protected String DYNAMIC_LINKER      = "/lib/ld-linux.so.2";
    static final protected String C_RUNTIME_INIT      = "/usr/lib/crti.o";
    static final protected String C_RUNTIME_START     = "/usr/lib/crt1.o";
    static final protected String C_RUNTIME_START_PIE = "/usr/lib/Scrt1.o";
    static final protected String C_RUNTIME_FINI      = "/usr/lib/crtn.o";

    protected void generateExecutable(Options opts) throws IPCException {
        List<Object> cmd = new ArrayList<Object>();
        cmd.add("ld");
        cmd.add("-dynamic-linker");
        cmd.add(DYNAMIC_LINKER);
        if (opts.isGeneratingPIE()) {
            cmd.add("-pie");
        }
        if (! opts.noStartFiles()) {
            cmd.add(opts.isGeneratingPIE()
                    ? C_RUNTIME_START_PIE
                    : C_RUNTIME_START);
            cmd.add(C_RUNTIME_INIT);
        }
        cmd.addAll(opts.ldArgs());
        if (! opts.noDefaultLibs()) {
            cmd.add("-lc");
            cmd.add("-lcbc");
        }
        if (! opts.noStartFiles()) {
            cmd.add(C_RUNTIME_FINI);
        }
        cmd.add("-o");
        cmd.add(opts.exeFileName());
        invoke(cmd, opts.isVerboseMode());
    }

    protected void generateSharedLibrary(Options opts) throws IPCException {
        List<Object> cmd = new ArrayList<Object>();
        cmd.add("ld");
        cmd.add("-shared");
        if (! opts.noStartFiles()) {
            cmd.add(C_RUNTIME_INIT);
        }
        cmd.addAll(opts.ldArgs());
        if (! opts.noDefaultLibs()) {
            cmd.add("-lc");
            cmd.add("-lcbc");
        }
        if (! opts.noStartFiles()) {
            cmd.add(C_RUNTIME_FINI);
        }
        cmd.add("-o");
        cmd.add(opts.soFileName());
        invoke(cmd, opts.isVerboseMode());
    }

    protected void invoke(List<Object> cmdArgs, boolean debug) throws IPCException {
        if (debug) {
            dumpCommand(cmdArgs);
        }
        try {
            String[] cmd = getStrings(cmdArgs);
            Process proc = Runtime.getRuntime().exec(cmd);
            proc.waitFor();
            passThrough(proc.getInputStream());
            passThrough(proc.getErrorStream());
            if (proc.exitValue() != 0) {
                errorHandler.error(cmd[0] + " failed."
                                   + " (status " + proc.exitValue() + ")");
                throw new IPCException("compile error");
            }
        }
        catch (InterruptedException ex) {
            errorHandler.error("gcc interrupted: " + ex.getMessage());
            throw new IPCException("compile error");
        }
        catch (IOException ex) {
            errorHandler.error(ex.getMessage());
            throw new IPCException("compile error");
        }
    }

    protected String[] getStrings(List<Object> list) {
        String[] a = new String[list.size()];
        int idx = 0;
        for (Object o : list) {
            a[idx++] = o.toString();
        }
        return a;
    }

    protected void dumpCommand(List<Object> args) {
        String sep = "";
        for (Object arg : args) {
            System.out.print(sep); sep = " ";
            System.out.print(arg.toString());
        }
        System.out.println("");
    }

    protected void passThrough(InputStream s) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(s));
        String line;
        while ((line = r.readLine()) != null) {
            System.err.println(line);
        }
    }

    protected void writeFile(String path, String str)
                                    throws FileException {
        try {
            BufferedWriter f = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(path)));
            try {
                f.write(str);
            }
            finally {
                f.close();
            }
        }
        catch (FileNotFoundException ex) {
            errorHandler.error("file not found: " + path);
            throw new FileException("file error");
        }
        catch (IOException ex) {
            errorHandler.error("IO error" + ex.getMessage());
            throw new FileException("file error");
        }
    }
}
