package org.apache.hadoop.fs;

import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.JobConf;

@SuppressWarnings("unchecked")

/**
 * InputStream for RCFile
 * 
 * @author mingfeng
 */
public class RCFileRecordInputStream extends InputStream {
  	//RCFileRecordReader r;
    LongWritable key = new LongWritable(0);
    //BytesRefArrayWritable val = new BytesRefArrayWritable();
    Text txt = new Text();

	DataInputBuffer inbuf;
	DataOutputBuffer outbuf;
	
	@SuppressWarnings("deprecation")
	public RCFileRecordInputStream(FileSystem fs, Path f, Configuration jobConf) throws IOException {
		super();
		/*
		long length = fs.getFileStatus(f).getLen();
		FileSplit split = new FileSplit(f, 0, length,(String[])null);
	    r = new RCFileRecordReader(jobConf, split);	  
		inbuf = new DataInputBuffer();
		outbuf = new DataOutputBuffer();
		*/
	}
	
	@Override
	public int read() throws IOException {
		/*int ret;
		if (null == inbuf || -1 == (ret = inbuf.read())) {
			if (!r.next(key, val)) {
				return -1;
			}
			for (int i = 0; i < val.size(); i++) {
				BytesRefWritable v = val.get(i);
		        txt.set(v.getData(), v.getStart(), v.getLength());
				outbuf.writeBytes(txt.toString());
		        if (i < val.size() - 1)
		        	outbuf.write('\u0001');
			}					
        	outbuf.write('\n');
			inbuf.reset(outbuf.getData(), outbuf.getLength());
			outbuf.reset();
			ret = inbuf.read();
		}
		*/
		return -1;
	}		
	}

