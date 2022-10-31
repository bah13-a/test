package com.example.test.Controller;

import com.example.test.Model.tagread;
import com.example.test.Service.TagRFIDService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;

@RestController
public class tagRFIDController {
    @Autowired
    public TagRFIDService tagService;
    @GetMapping("/map")
    public tagread getTagRfid() throws IOException {
       return tagService.TagMapper();
    }

    @PostMapping("/injecter")
    public tagread saveTag() throws FileNotFoundException {
        return tagService.SaveTag();
    }
}
