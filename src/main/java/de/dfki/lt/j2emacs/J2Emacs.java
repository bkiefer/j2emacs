package de.dfki.lt.j2emacs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Layout;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;

public class J2Emacs {

  /** A log4j appender that just outputs the message of the logging event in
   *  plain form to an emacs buffer.
   */
  public class EmacsBufferAppender extends AppenderSkeleton {
    private String _bufferName;
    private Layout _mylayout = new PatternLayout("%p: %m%n");
    private boolean _compilationMode;

    public EmacsBufferAppender(String bufName, boolean compilationMode) {
      _bufferName = bufName;
      _compilationMode = compilationMode;
      if (_compilationMode) {
        createCompilationBuffer(_bufferName);
      }
    }

    public EmacsBufferAppender() { this("*log*", false); }

    @Override
    protected void append(LoggingEvent arg0) {
      if (_compilationMode) {
        createCompilationBuffer(_bufferName);
      }
      appendToBuffer(_bufferName, _mylayout.format(arg0));
    }

    public void close() { closed = true; killBuffer(_bufferName); }

    public boolean requiresLayout() { return true; }
  }

  private static Logger log = Logger.getLogger("J2Emacs");

  /** The visible name of the application that uses this connection. Shown in
   *  emacs buffers and menus.
   */
  private String _appname;

  private String _defaultCmd ="emacs";

  private String _host = "localhost";
  private int _port = 4444;

  private String _lispFile = "j2e.el";

  /** the directory where to find the lispFile, or null */
  private File _resourceDir;

  private static final Pattern splitReg =
    Pattern.compile(" *([^\"][^ ]*|\"(?:[^\\\"]*|\\.)*\")");

  private ServerSocket _socket;
  private Socket _clientSocket;
  private BufferedReader _in;
  private PrintWriter _out;

  private Thread _commandEvaluator;

  private List<String> _startHooks;

  public interface Action { void execute(String ... args); }

  /** to increase efficiency, output to, e.g., compilation buffers can be
   *  buffered internally and passed to emacs later as a bigger chunk.
   *
   *  The buffers for the buffered emacs buffers, represented by their names,
   *  are stored here.
   */
  private HashMap<String, StringBuilder> _buffering;

  private HashMap<String, Action> _actions;

  /** Try to find a free TCP/IP port. Return true on failure */
  private boolean openSocket() {
    int last = _port + 1000;
    while (_socket == null && _port < last) {
      try {
        _socket = new ServerSocket(_port);
      } catch (IOException e) {
        _socket = null;
        _port += 20;
      }
    }
    return _socket == null;
  }

  public J2Emacs(String appname, File resourceDir, String emacsPath) {
    _appname = appname;
    _socket = null;
    _clientSocket = null;
    _in = null;
    _out = null;
    _actions = new HashMap<String, Action>();
    _startHooks = new ArrayList<String>();
    _resourceDir = resourceDir;
    _buffering = new HashMap<String, StringBuilder>();
    if (emacsPath != null) {
      _defaultCmd = emacsPath;
    }
  }

  public synchronized void close() {
    if (_out != null) _out.close();
    _out = null;
    if (_commandEvaluator != null) {
      _commandEvaluator.interrupt();
    }
    try { if (_in != null) _in.close();  }
    catch (IOException ex) { log.warn(ex.toString()); }
    _in = null;
    if (_commandEvaluator != null && _commandEvaluator.isAlive()) {
      _commandEvaluator.interrupt();
    }
    try { if (_clientSocket != null) _clientSocket.close(); }
    catch (IOException ex) { log.warn(ex.toString()); }
    _clientSocket = null;
    try { if (_socket != null) _socket.close(); }
    catch (IOException ex) { log.warn(ex.toString()); }
    _socket = null ;

  }

  public synchronized boolean startEmacs() {
    return ensureEmacsRunning();
  }

  private File getElispFile() {
    File result =
      (_resourceDir == null
          ? new File(_lispFile) : new File(_resourceDir, _lispFile));
    return result;
  }

  public void registerAction(String key, Action action) {
    _actions.put(key, action);
  }

  private void eval(String cmdString) {
    cmdString = cmdString.trim();
    ArrayList<String> tokens = new ArrayList<String>();
    Matcher m = splitReg.matcher(cmdString);
    while (m.find()) {
      tokens.add(m.group(1));
    }
    Action action = _actions.get(tokens.get(0));
    if (action == null) {
      log.warn("No such action: " + tokens.get(0));
    }
    else {
      tokens.remove(0);
      String[] arr = new String[tokens.size()];
      action.execute(tokens.toArray(arr));
    }
  }

  /** Return \c true if the the current J2Emacs is in a live state, false
   *  otherwise */
  public boolean alive() {
    if (_socket == null || _out == null ||  _out.checkError()) {
      close();
      return false;
    }
    return true;
  }


  private boolean ensureEmacsRunning() {
    String cmd = _defaultCmd;
    if (! alive()) {
      if (openSocket()) {
        close();
        return false;
      }

      // start Emacs
      File elispFile = getElispFile();
      String loadCmd = "(progn (add-to-list 'load-path \""
        + elispFile.getAbsoluteFile().getParent() +"\") (require 'j2e)"
        + "(j2e-startup \"" + _appname + "\" \"" + _host + "\" " + _port + "))";
      try {
        String[] cmdarray = { cmd, "--eval", loadCmd };
        Runtime.getRuntime().exec(cmdarray);
      }
      catch (IOException ex) {
        log.error(ex.toString());
        close();
      }

      try {
        _clientSocket = _socket.accept();
        _out = new PrintWriter(_clientSocket.getOutputStream(), true);
        _in = new BufferedReader(
            new InputStreamReader(_clientSocket.getInputStream()));
        _commandEvaluator =
          new Thread(
              new Runnable() {
                public void run() {
                  while (true) {
                    StringBuffer sb = new StringBuffer();
                    try {
                      int block = _in.read();
                      if (block == -1) return;
                      sb.append((char) block);
                      while (_in.ready())
                        sb.append((char) _in.read());
                    }
                    catch (IOException ioex) {
                      close();
                      return;
                    }
                    String command = sb.toString();
                    if (! command.isEmpty())
                      eval(command);
                  }
                }
              },
          "J2EcommandEvaluator");
        _commandEvaluator.start();
        // TODO remove if stable
        //_out.append("(setq debug-on-error t)"); _out.flush();
        for (String sexp : _startHooks) {
          _out.append(sexp);
        }
        _out.flush();
      } catch (IOException e) {
        log.error("Accept failed: " + _host + ":" + _port);
        close();
      }
    }
    return (_socket != null);
  }


  public boolean evalElisp(String sexp) {
    if (! ensureEmacsRunning()) return true;
    synchronized (_out) {
      _out.append(sexp);
      _out.flush();
      return false;
    }
  }

  /** if state=="disabled", file is opened read-only */
  public boolean visitFilePosition(File file, int line, int col, String state) {
    return evalElisp("(j2e-visit \"" + file.getParent() + "\" \""
        + file.getName() + "\" " + line + " " + col + " \"" + state + "\")");
  }

  public boolean exitEmacs() {
    if (! alive()) return true;
    return evalElisp("(save-buffers-kill-emacs)");
  }

  public boolean killBuffer(String name) {
    return evalElisp("(j2e-kill-buffer \"" + name + "\")");
  }

  public boolean fillBuffer(String name, Reader in) {
    if (! ensureEmacsRunning()) return true;
    _out.append(
        "(save-excursion " +
          "(with-current-buffer (get-buffer-create \"" + name + "\")" +
             "(goto-char (point-max))");
    try {
      _out.append("(insert \"");
      int nextInt = in.read();
      while (nextInt != -1) {
        _out.append((char) nextInt);
        nextInt = in.read();
      }
    } catch (IOException e) {
      log.error(e.toString());
    }
    finally {
      _out.append("\")))");
      _out.flush();
    }
    return false;
  }

  public boolean appendToBuffer(String name, String what) {
    StringBuilder sb = _buffering.get(name);
    if (sb != null) {
      sb.append(what);
      return false;
    }
    return evalElisp("(j2e-append-to-buffer \"" + name + "\" \"" + what + "\")");
  }

  public boolean clearBuffer(String name) {
    return evalElisp("(j2e-clear-buffer \"" + name + "\")");
  }

  public boolean createCompilationBuffer(String name) {
    return evalElisp("(j2e-compilation-buffer \"" + name + "\")");
  }

  public boolean markAsProjectFiles(File rootDirectory, List<File> files) {
    StringBuilder sb = new StringBuilder();
    sb.append("(j2e-project-files \"" + rootDirectory.getPath() + "\" '( ");
    for (File f : files) {
      sb.append("\""+ f.getAbsolutePath() + "\" ");
    }
    sb.append("))");
    return evalElisp(sb.toString());
  }

  /** The command (sexp) in the string will be sent to emacs if it is restarted,
   *  e.g., to load a major mode for the application
   */
  public void addStartHook(String string) {
    _startHooks.add(string);
  }

  public void startBuffering(String name) {
    if (! _buffering.containsKey(name)) {
      _buffering.put(name, new StringBuilder());
    }
  }

  public void flushBuffer(String name) {
    if (_buffering.containsKey(name)) {
      String output = _buffering.get(name).toString();
      _buffering.remove(name);
      appendToBuffer(name, output);
    }
  }

  /*
  public static void main(String[] args) throws IOException {
    J2Emacs j2e = new J2Emacs();
    j2e.startEmacs(new File("/home/kiefer/ing"), 30, 7, "");
    if (j2e._out.checkError()) log.warn("out error");

    j2e.startEmacs(new File("/home/kiefer/here"), 3, 7, "");
    j2e.startEmacs(new File("/home/kiefer/ing"), 3, 7, "");

    j2e.fillBuffer("*test*", new StringReader("foobar"));
    j2e.clearBuffer("*test*");
    j2e.fillBuffer("*test*", new StringReader("barfoo"));
    j2e.createCompilationBuffer("*compilation*");
    j2e.fillBuffer("*compilation*",
        new StringReader("\nfoo\nerror: /home/kiefer/ing:70:3\n"));

    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    String line;
    while(false && ! (line = in.readLine()).isEmpty()) {
      switch (line.charAt(0)) {
        case '<': j2e.startEmacs(new File(line.substring(1)),0,0,""); break;
        case '>': j2e.fillBuffer("test", new StringReader(line.substring(1)));break;
        default:
          j2e._out.append(line); j2e._out.flush(); break;
      }
    }
  }
  */
}
