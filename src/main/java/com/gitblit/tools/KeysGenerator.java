/*
 * Copyright 2012 James Moger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class KeysGenerator {
  private static final int BUFFER_SIZE = 8192;

  @Option(name = "-out", usage = "output zip file")
  private String zipFile;

  @Option(name = "-properties", usage = "properties input path")
  File propertiesFile;

  @Option(name = "-classname", usage = "destination class file name")
  String className;

  @Option(name = "-tmp", usage = "temp output path")
  File tempDir;

  /** Creates a new byte array for buffering reads or writes. */
  static byte[] createBuffer() {
    return new byte[BUFFER_SIZE];
  }

  /**
   * Copies all bytes from the input stream to the output stream. Does not close or flush either
   * stream.
   *
   * @param from the input stream to read from
   * @param to the output stream to write to
   * @return the number of bytes copied
   * @throws IOException if an I/O error occurs
   */
  public static long copy(InputStream from, OutputStream to) throws IOException {
    byte[] buf = createBuffer();
    long total = 0;
    while (true) {
      int r = from.read(buf);
      if (r == -1) {
        break;
      }
      to.write(buf, 0, r);
      total += r;
    }
    return total;
  }

  public void execute(String... parameters) throws IOException {
    CmdLineParser parser = new CmdLineParser(this);
    try {
      parser.parseArgument(parameters);
      if (tempDir == null) {
        throw new CmdLineException("Please specify a temporary directory!");
      }

      if (className == null) {
        throw new CmdLineException("Please specify an output classname!");
      }

      if (propertiesFile == null) {
        throw new CmdLineException("Please specify an input properties file!");
      }

    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
      return;
    }

    // Load all keys
    Properties properties = new Properties();
    FileInputStream is = null;
    try {
      is = new FileInputStream(propertiesFile);
      properties.load(is);
    } catch (Throwable t) {
      t.printStackTrace();
    } finally {
      if (is != null) {
        try {
          is.close();
        } catch (Throwable t) {
          // IGNORE
        }
      }
    }
    List<String> keys = new ArrayList<String>(properties.stringPropertyNames());
    Collections.sort(keys);

    KeyGroup root = new KeyGroup();
    for (String key : keys) {
      root.addKey(key);
    }

    // Save Keys class definition
    String entry = className.replace('.', '/') + ".java";
    File file = new File(tempDir, entry);
    file.getParentFile().mkdirs();
    FileWriter fw = new FileWriter(file, false);
    fw.write(root.generateClass(className));
    fw.close();

    // Create zip file
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(Paths.get(zipFile)))) {
      zipFile(file, entry, zip);
    }
  }

  public static void zipFile(File file, String name, ZipOutputStream zip) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    try (InputStream input = Files.newInputStream(file.toPath())) {
      copy(input, zip);
    }
    zip.closeEntry();
  }

  public static void main(String[] args) {
    try {
      new KeysGenerator().execute(args);
    } catch (IOException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }

  private static class KeyGroup {
    final KeyGroup parent;
    final String namespace;

    String name;
    List<KeyGroup> children;
    List<String> fields;

    KeyGroup() {
      this.parent = null;
      this.namespace = "";
      this.name = "";
    }

    KeyGroup(String namespace, KeyGroup parent) {
      this.parent = parent;
      this.namespace = namespace;
      if (parent.children == null) {
        parent.children = new ArrayList<KeyGroup>();
      }
      parent.children.add(this);
    }

    /**
     * Left pad a string with the specified character, if the string length is less than the
     * specified length.
     *
     * @param input
     * @param length
     * @param pad
     * @return left-padded string
     */
    public static String leftPad(String input, int length, char pad) {
      if (input.length() < length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = length - input.length(); i < len; i++) {
          sb.append(pad);
        }
        sb.append(input);
        return sb.toString();
      }
      return input;
    }

    void addKey(String key) {
      String keyspace = "";
      String field = key;
      if (key.indexOf('.') > -1) {
        keyspace = key.substring(0, key.lastIndexOf('.'));
        field = key.substring(key.lastIndexOf('.') + 1);
        KeyGroup group = addKeyGroup(keyspace);
        group.addKey(field);
      } else {
        if (fields == null) {
          fields = new ArrayList<String>();
        }
        fields.add(key);
      }
    }

    KeyGroup addKeyGroup(String keyspace) {
      KeyGroup parent = this;
      KeyGroup node = null;
      String[] space = keyspace.split("\\.");
      for (int i = 0; i < space.length; i++) {
        StringBuilder namespace = new StringBuilder();
        for (int j = 0; j <= i; j++) {
          namespace.append(space[j]);
          if (j < i) {
            namespace.append('.');
          }
        }
        if (parent.children != null) {
          for (KeyGroup child : parent.children) {
            if (child.name.equals(space[i])) {
              node = child;
            }
          }
        }
        if (node == null) {
          node = new KeyGroup(namespace.toString(), parent);
          node.name = space[i];
        }
        parent = node;
        node = null;
      }
      return parent;
    }

    String fullKey(String field) {
      if (namespace.equals("")) {
        return field;
      }
      return namespace + "." + field;
    }

    String generateClass(String fqn) {
      String packageName = "";
      String className = fqn;
      if (fqn.indexOf('.') > -1) {
        packageName = fqn.substring(0, fqn.lastIndexOf('.'));
        className = fqn.substring(fqn.lastIndexOf('.') + 1);
      }

      StringBuilder sb = new StringBuilder();
      sb.append("package ").append(packageName).append(";\n");
      sb.append('\n');
      sb.append("/*\n");
      sb.append(" * This class is auto-generated from a properties file.\n");
      sb.append(" * Do not version control!\n");
      sb.append(" */\n");
      sb.append(MessageFormat.format("public final class {0} '{'\n\n", className));
      sb.append(generateClass(this, 0));
      sb.append("}\n");
      return sb.toString();
    }

    String generateClass(KeyGroup group, int level) {
      String classIndent = leftPad("", level, '\t');
      String fieldIndent = leftPad("", level + 1, '\t');

      // begin class
      StringBuilder sb = new StringBuilder();
      if (!group.namespace.equals("")) {
        sb.append(classIndent)
            .append(MessageFormat.format("public static final class {0} '{'\n\n", group.name));
        sb.append(fieldIndent)
            .append(
                MessageFormat.format(
                    "public static final String _ROOT = \"{0}\";\n\n", group.namespace));
      }

      if (group.fields != null) {
        // fields
        for (String field : group.fields) {
          sb.append(fieldIndent)
              .append(
                  MessageFormat.format(
                      "public static final String {0} = \"{1}\";\n\n",
                      field, group.fullKey(field)));
        }
      }
      if (group.children != null) {
        // inner classes
        for (KeyGroup child : group.children) {
          sb.append(generateClass(child, level + 1));
        }
      }
      // end class
      if (!group.namespace.equals("")) {
        sb.append(classIndent).append("}\n\n");
      }
      return sb.toString();
    }
  }
}
