/*
 * Copyright 2007  T-Rank AS
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
package no.trank.openpipe.opennlp.io;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import opennlp.maxent.io.BinaryGISModelReader;

/**
 * @version $Revision$
 */
public class InputStreamGISModelReader extends BinaryGISModelReader {

   public InputStreamGISModelReader(InputStream in) throws IOException {
      this(in, false);
   }
   
   public InputStreamGISModelReader(InputStream in, boolean gzipped) throws IOException {
      super(gzipped ? new DataInputStream(new GZIPInputStream(in)) : new DataInputStream(in));
   }
}
