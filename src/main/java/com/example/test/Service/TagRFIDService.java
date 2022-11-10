package com.example.test.Service;

import com.example.test.Model.tagread;
import com.example.test.Repository.TagRFIDRepository;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class TagRFIDService {
    @Autowired
    private TagRFIDRepository tagRepo;
    private String path ;



    public tagread TagMapper() throws FileNotFoundException {
        Gson gson=new Gson();

        InputStream is = getClass().getResourceAsStream("/RFID.json");
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        tagread tag=gson.fromJson(reader,tagread.class);

        if(!this.valide(tag))
        {
            System.out.println("ERREUR");
        }
        return tag;
    }
    public boolean valide(tagread tag)
    {
        if(!((tag.getEPC().length()==24)&&(Integer.toHexString(Integer.parseInt(tag.getEPC())).length()==0)&&(tag.getAntenna()>0)&&(tag.getAntenna()<5)&&((String.valueOf(tag.getTimestamp())).length()==13)&&((tag.getRSSI()<0)&&(tag.getRSSI()>-70))))
        {return true;}
        else
            return false;
    }
    public tagread SaveTag() throws FileNotFoundException {
        tagread tag=this.TagMapper();
        tagRepo.saveAndFlush(tag);
        return  tagRepo.save(tag);
    }
   public String importer(MultipartFile file ) {

        String fileName = file.getOriginalFilename();
        try {
            file.transferTo( new File("C:\\Users\\user\\Desktop\\timestampappli\\Test\\src\\main\\resources\\" +"RFID.json"));
        } catch (Exception e) {
            path=file.getOriginalFilename();
            return path ;//ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
       path=file.getOriginalFilename();
        return path+"importé" ;//ResponseEntity.ok("File uploaded successfully.");
    }




}
