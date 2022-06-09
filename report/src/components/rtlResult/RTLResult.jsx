import React, { useEffect, useState } from 'react'
import Table from 'react-bootstrap/Table'
import Button from 'react-bootstrap/Button'
import Collapse from 'react-bootstrap/Collapse'
import Container from 'react-bootstrap/Container'
import './styles.css'

const RTLResult = ({infoRTL}) =>{
    const [open, setOpen] = useState(false);
    const [states, setStates] = useState([]);
    const [pathEng, setPathEng] = useState('');
    const [pathArabRep, setPathArabRep] = useState('');
    const [pathArabOri, setPathArabOri] = useState('');


    useEffect(() => {  
        let arrayStates = [];
        for (let state = 1; state <= infoRTL.states; state++) {
            arrayStates.push(state);
            
        }
        setStates(arrayStates);
        Object.values(infoRTL.languages).forEach(lang =>{
            fetch(
            `./results/trnsResults/${lang}/result.json`
            )
            .then((res) => res.json())
            .then((res) => {
                console.log(lang)
                if(lang === "ar")
                    setPathArabRep(`./results/trnsResults/${lang}`)
                else if(lang === "en")
                    setPathEng(`./results/trnsResults/${lang}`)
                else if(lang === "ar-original")
                    setPathArabOri(`./results/trnsResults/${lang}`)
            })
            .catch((err) => {
                console.log(lang)
                fetch(
                `./results/noTrnsResults/${lang}/result.json`
                )
                .then((res) => res.json())
                .then((res) => {
                    if(lang === "ar")
                        setPathArabRep(`./results/noTrnsResults/${lang}`)
                    else if(lang === "en")
                        setPathEng(`./results/noTrnsResults/${lang}`)
                    else if(lang === "ar-original")
                        setPathArabOri(`./results/noTrnsResults/${lang}`)
                })
                .catch((err) => console.error(err))
                })
          
        })
    }, [infoRTL.languages, infoRTL.states, pathArabRep])
   




    return (
        <Container fluid="lg" className="mb-5">
        <div id="resultRTL" >
        <h2>Right-to-left Languages Repairment Results                  
            <Button id="btnAbout"
            variant="info" 
            size="sm"
            onClick={() => setOpen(!open)}
            aria-controls="example-collapse-text"
            aria-expanded={open}
            > ?
            </Button></h2>
            <Collapse in={open}>
                <div id="aboutRTL">
                   <p>The following images show the results of adding support of right-to-left languages with the native Android implementation.</p>
                   <p>If the repairment was not made it could be because the app didn't declare a target SDK version, or the version declared was lower than 17.</p>  
                </div>
            </Collapse>
        <Container  fluid="lg" className="summary-card pt-2 pb-2 shadow">
        <div>
        {infoRTL.repairedRTL?(
                    <Table id="rtlTable" striped bordered hover className="text-center">
                    <thead>
                  <tr>
                    <th title="Content of the HCS">State</th>
                    <th title="English Version">English Version</th>
                    <th title="Arabic Original Version">Arabic Original Version</th>
                    <th title="Arabic Repaired Version">Arabic Repaired Version</th>
        
                  </tr>
                </thead>
                
                    {states.map((state) =>(
                    <tbody>
                        <tr>
                        <td>{state}</td>
                        <td><img id="imgRTL" src={pathEng+"/"+state+".png"} alt= {"English state "+state} width = "288" height="512"/></td>
                        <td><img id="imgRTL" src={pathArabOri+"/"+state+".png"} alt= {"Arabic original state "+state} width = "288" height="512"/></td>
                        <td><img id="imgRTL" src={pathArabRep+"/"+state+".png"} alt= {"Arabic repaired state "+state} width = "288" height="512"/></td>
                        </tr>
                    </tbody>
                    ))
                    }
                </Table>
        ):(
        <div id="noRTL">
            <div id="noRTL">
                <img id="noMirror" src="./noMirroring.png" alt="no mirroring"/>
            </div>
            <p>The repairment of the layout for right-to-left languages was not carried out.</p>
        </div>)}
        </div>
        </Container>
        </div>
        </Container>
    )

}

export default RTLResult