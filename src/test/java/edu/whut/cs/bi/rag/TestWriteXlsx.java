package edu.whut.cs.bi.rag;

import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.AIDatabase;
import com.tencent.tcvectordb.model.CollectionView;
import edu.whut.cs.bi.rag.controller.FileUploadController;
import edu.whut.cs.bi.rag.service.VectorDBClientOnlineService;
import edu.whut.cs.bi.rag.service.WriteXlsx2VectorDB;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = Application.class)
public class TestWriteXlsx {


    @Autowired
    WriteXlsx2VectorDB  writeXlsx2VectorDB;

    @Test
    public void test1() throws Exception {
        String path = "C:\\Users\\DK\\Downloads\\成因分析.xlsx";
        writeXlsx2VectorDB.writeXlsx2VectorDB(path);
    }



}
