import React, { useEffect, useState } from 'react'
import Table from 'react-bootstrap/Table'
import Button from 'react-bootstrap/Button'
import Collapse from 'react-bootstrap/Collapse'
import './styles.css'
const HCSTable = ({type, hcsStrings, title}) =>{
    const [open, setOpen] = useState(false);

    const renderSummary = () =>{
        if(type === '1')
           return (
               <>
               <p> These strings are the ones that were found as potentially hard coded and were replaced for a resource string. The translatable strings refer to these that could be displayed in the UI. They can be found in the strings resource file.</p>           
               </>
           );
        else if(type === "2")
        return (
            <>
            <p> These strings were found in parts of the code where for one or multiple reasons, it was not possible to access the resources of the app:</p>
            <p> &bull; The first possible case is that the strings were found in a static method, therefore, there’s no reference to any context, and this is needed to use the method getResources().</p>
            <p> &bull; The second case is similar to the first one, but instead they were found in a class that didn´t extend from Context or Activity, and also don’t get another context passed in any way, so there’s no access to the resources.</p>
            <p> The following strings were left untouched and are only reported.</p>
            </>
        );
        else if(type === "3")
        return (
            <>
            <p> These strings were found in the layout xml files, specifically in xml attributes such as, “android:text”, “android:hint” and “android:contentDescription”, that had hard coded string values and not a resource string reference. They were replaced for a given string resource id and then translated.</p>
            </>
        );
        return null;
     }

    return (
        <>
        <div> 
            <h4>{title}                  
            <Button id="btnAbout"
            variant="info" 
            size="sm"
            onClick={() => setOpen(!open)}
            aria-controls="example-collapse-text"
            aria-expanded={open}
            > ?
            </Button></h4> 

            <Collapse in={open}>
                <div id="about">
                {renderSummary()}
                </div>
            </Collapse>

        <div id="scrollable">
        <Table id="hcsTable" striped bordered hover className="text-center">
            <thead>
          <tr>
            <th title="Content of the HCS">String</th>
            {type === "2" || type === "1"?(<th title="Name of the package followed by the name of the file where the HCS was found">Package + Class File</th>)
                                         :(<th title="Name of the XML file where the HCS was found">Layout File</th>)}
            {type === "3" || type === "1"?(<th title="ID given to this string resource">String ID</th>):(<></>)}
            {type === "1"?(<th title="Name of the method where the string was found">Method Name</th>):(<></>)}

          </tr>
        </thead>
        
            {hcsStrings.map((string) =>(
            <tbody>
                <tr>
                <td>{string.stringContent}</td>
                <td>{string.className}</td>
                {type === "3" || type === "1"?(<td>{string.stringID}</td>):(<p></p>)}
                {type === "1"?(<td>{string.methodName}</td>):(<p></p>)}
                </tr>
            </tbody>
            ))
            }
        </Table>
        </div>
        </div>
        </>
    )

}

export default HCSTable